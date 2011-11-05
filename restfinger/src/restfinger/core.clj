(ns restfinger.core
  "This module saves your time by writing all the Create/Read/Update/Delete
  boilerplate for you. Flash messages, validation, inserting example data,
  customization via hooks, actions and channels -- you name it, this module does it."
  (:use (restfinger output default-views),
        formfinger.field-helpers,
        corefinger.core,
        basefinger.core,
        toolfinger,
        valip.core,
        lamina.core))

(def regexps {:format #"\.?[a-zA-Z]*"})

(defmacro respond [req matches status headers data custom default]
  `(render
     (getoutput
       (first
         (filter identity
            [(get-in ~req [:headers "accept"]) ; must be lowercase!
             (:format ~matches)
             ~default])) ~custom)
   ~status ~headers ~data))

(defn resource
  "Creates a list of two routes (/url-prefix+collname.format and
  /url-prefix+collname/pk.format) for RESTful Create/Read/Update/Delete
  of entries in collname.
  Also, while in development environment, you can create example data using faker,
  like this: /url-prefix+collname.format/_create_fakes?count=100 (the default count is 5).
  Accepted options:
   :db -- database (required!)
   :pk -- primary key (required!)
   :url-prefix -- a part of the URL before the collname, default is /
   :owner-field -- if you want entries to be owned by users, name of the field which should hold usernames
   :default-dboptions -- default database options (:query, :sort) for the index page
   :whitelist -- allowed extra fields (not required)
   :forbidden-methods -- a collection of methods to disallow (:index, :create, :read, :update, :delete)
   :views -- map of HTML views (:index, :get, :not-found)
   :flash -- map of flash messages (:created, :updated, :deleted, :forbidden),
             can be either strings or callables expecting a single arg (the entry)
   :hooks -- map of hooks (:data (on both create and update), :create, :update, :view), must be callables expecting
             the entry and returning it (with modifications you want). Hooks receive data with correct
             types, so eg. dates/times are org.joda.time.DateTime's and you can mess with them using clj-time
             Tip: compose hooks with comp
   :channels -- map of Lamina channels (:create, :update, :delete). Ringfinger will publish events
                to these channels so you could, for example, push updates to clients in real time,
                enqueue long-running jobs, index changes with a search engine, etc.
   :actions -- map of handlers for custom actions (callables accepting [req matches entry default-data])
               on resource entries, called by visiting /url-prefix+collname/pk?_action=action"
  [collname {:keys [db pk owner-field default-dboptions url-prefix whitelist
                    actions views forbidden-methods flash channels hooks]
             :or {db nil pk nil owner-field nil
                  default-dboptions {} url-prefix "/"
                  whitelist nil actions []
                  views {:index default-index
                         :get default-get
                         :not-found default-not-found}
                  forbidden-methods [] flash nil
                  channels {} hooks {}
                  }} & fields]
  (let [coll (keyword collname)
        urlbase (str url-prefix collname)
        fieldhtml (html-from-fields fields)
        valds (validations-from-fields fields)
        fakers (fakers-from-fields fields)
        req-fields (required-fields-of fields)
        blank-entry (zipmap req-fields (repeat ""))
        default-entry (defaults-from-fields fields)
        whitelist (let [w (concat (or whitelist (filter identity (list owner-field))) (keys fieldhtml))]
        ; cut off :csrftoken, don't allow users to store everything
                    (concat w (map #(keyword (str (name %) "_slug")) w)))
        actions (zipmap (map name (keys actions)) (vals actions))
        default-data (pack-to-map coll db collname pk fieldhtml actions urlbase)
        html-index (html-output (:index views) default-data)
        html-get   (html-output (:get   views) default-data)
        html-not-found (html-output (:not-found views) default-data)
        s-channels [:create :update :delete]
        channels (zipmap s-channels
                   (map #(let [c (get channels %)]
                           (if c (fn [msg] (enqueue c msg)) (fn [msg]))) s-channels))
        flash (or flash {:created #(str "Created: " (get % pk))
                         :updated #(str "Saved: "   (get % pk))
                         :deleted #(str "Deleted: " (get % pk))
                         :forbidden "Forbidden."})
        hooks (merge (zipmap [:data :create :update :read] (repeat identity)) hooks)
        ; --- functions ---
        clear-form #(select-keys % whitelist)
        fields-data-pre-hook  (data-pre-hook-from-fields  fields)
        fields-data-post-hook (data-post-hook-from-fields fields)
        post-hook (comp clear-form fields-data-pre-hook (:data hooks) (:create hooks) fields-data-post-hook)
        put-hook  (comp clear-form fields-data-pre-hook (:data hooks) (:update hooks) fields-data-post-hook)
        get-hook  (comp (:read hooks) (get-hook-from-fields fields))
        i-validate (fn [req data yep nope]
                     (let [ks (filter #(or (haz? req-fields %) (not (or (= (get data %) "") (nil? (get data %))))) (keys data))
                           result (apply validate (select-keys data ks)
                                         (filter #(haz? ks (first %)) valds))]
                       (if (= result nil) (yep) (nope result))))
        i-get-one  #(get-one db coll {:query {pk (typeify (:pk %))}})
        i-redirect (fn [req matches form flash status]
                     {:status  status
                      :headers {"Location" (str urlbase "/" (get form pk) (dotformat matches))}
                      :flash   (call-or-ret flash form)
                      :body    ""})
        i-get-dboptions (if owner-field
                      #(assoc-in (or (params-to-dboptions (:query-params %)) default-dboptions) [:query owner-field] (get-in % [:user :username]))
                      #(or (params-to-dboptions (:query-params %)) default-dboptions))
        i-respond-404 (fn [req matches]
                        (respond req matches 404 {}
                                 {:req req}
                                 {"html" html-not-found}
                                 "html"))
        if-allowed  (if owner-field
                      (fn [req entry yep]
                        (if (and (= (get-in req [:user :username]) (get entry owner-field))
                                    (not (= nil (get entry owner-field))))
                          (yep)
                          (if (from-browser? req)
                            {:status  302
                             :headers {"Location" urlbase}
                             :flash   (call-or-ret (:forbidden flash) entry)
                             :body    ""}
                            {:status  403
                             :headers {"Content-Type" "text/plain"}
                             :body    "Forbidden"})))
                       #(%3))
        process-new  (if owner-field
                       ; [req form]
                       ; adds creator's username if there's an owner-field
                       #(assoc (post-hook %2) owner-field (get-in %1 [:user :username]))
                       #(post-hook %2))
        if-not-forbidden #(if (not (haz? forbidden-methods %1)) %2 method-na-handler)]
     (list
       (route (str urlbase ":format")
         {:get (if-not-forbidden :index
                  (fn [req matches]
                    (respond req matches 200 {"Link" (str "<" urlbase "/{" (name pk) "}.{format}>; rel=\"entry\"")}
                          {:req  req
                           :data (map get-hook (get-many db coll (i-get-dboptions req)))}
                          {"html" html-index}
                          "html")))
          :post (if-not-forbidden :create (fn [req matches]
                  (let [form  (keywordize (:form-params req))
                        entry (process-new req form)]
                    (i-validate req (merge blank-entry form)
                      (fn []
                        ((:create channels) entry)
                        (create db coll entry)
                        (i-redirect req matches entry (:created flash) (if (from-browser? req) 302 201)))
                      (fn [errors]
                        (respond req matches 400 {}
                                 {:data (map get-hook (get-many db coll (i-get-dboptions req)))
                                  :newdata form
                                  :req req
                                  :errors errors}
                                 {"html" html-index}
                                 "html"))))))} regexps)
       (if-env "development"
         (route (str urlbase "/_create_fakes")
           {:get (fn [req matches]
                   (create-many db coll
                     (take (Integer/parseInt (get-in req [:query-params "count"] "5"))
                           (repeatedly (fn [] (process-new req (zipmap (keys fakers) (map #(last (take (rand-int 1000) %)) (vals fakers))))))))
                   {:status  302
                    :headers {"Location" (str urlbase (dotformat matches))}
                    :body    nil})})
         nil)
       (route (str urlbase "/:pk:format")
         {:get (fn [req matches]
                 (if-let [entry (i-get-one matches)]
                   (if-let [action (get actions (get-in req [:query-params "_action"] ""))]
                     (action req matches entry default-data)
                     (if-not-forbidden :read
                       (respond req matches 200 {}
                                {:data (get-hook entry)
                                 :req req}
                                {"html" html-get}
                              "html")))
                 (i-respond-404 req matches)))
          :put (if-not-forbidden :update (fn [req matches]
                 (let [form (keywordize (:form-params req))
                       orig (i-get-one matches)
                       diff (put-hook (merge default-entry form))
                       merged (merge orig diff)]
                   (if-allowed req orig
                     (fn []
                       (i-validate req form
                         (fn []
                           ((:update channels) merged)
                           (update db coll orig diff)
                           (i-redirect req matches merged (:updated flash) 302))
                         (fn [errors]
                           (respond req matches 400 {}
                                    {:data (merge orig form) ; with form! so users can correct errors
                                     :req req
                                     :errors errors}
                                    {"html" html-get}
                                    "html"))))))))
          :delete (if-not-forbidden :delete (fn [req matches]
                    (if-let [entry (i-get-one matches)]
                      (if-allowed req entry
                        (fn []
                          ((:delete channels) entry)
                          (delete db coll entry)
                          {:status  302
                           :headers {"Location" urlbase}
                           :flash   (call-or-ret (:deleted flash) entry)
                           :body    nil}))
                      (i-respond-404 req matches))))} regexps))))

(defmacro defresource [nname options & fields]
  ; dirty magic
  (intern *ns* nname
    (let [nnname (str nname)]
      (eval `(resource ~nnname ~options ~@fields)))))

(ns authfinger.core
  "Low-level authorization API (creating users, getting users after checking) and the auth middleware."
  (:use (basefinger core inmem),
        toolfinger,
        secfinger,
        [clojure.string :only [split]])
  (:import org.apache.commons.codec.digest.DigestUtils,
           org.apache.commons.codec.binary.Base64,
           java.util.UUID))

(def ^:dynamic *fixed-salt-part* "186c47add4608abb4c198ef1eac07e41")
(def ^:dynamic *login-field* :username)

(defmacro with-salt
  "Changes the fixed part of the salt used for password hashing.
   Wrap both app call and auth-routes calls (they're usually nested,
   but you're free to (def something (auth-routes {…})), right?).
   And (make|get)-user call if you do them (in tests?)
   Change the salt once to a random value and NEVER change it later
   (or your app's users will seriously hate you)"
  [salt & body]
  `(do
     (let [s# ~salt]
       (assert (string? s#))
       (binding [*fixed-salt-part* s#]
         ~@body))))

(defmacro with-login-field
  "Changes the field used for logging in, default is :username"
  [login-field & body]
  `(do
     (let [l# ~login-field]
       (assert (keyword? l#))
       (binding [*login-field* l#]
         ~@body))))

(defn get-user
  "Returns a user from coll in db with given
  login field value and password if the password is valid"
  [db coll login password]
  (let [user (get-one db coll {:query {*login-field* login}})]
    (if (= (:hash user) (DigestUtils/sha256Hex (str (:salt user) *fixed-salt-part* password)))
      (if (nil? (:_confirm_key user)) user nil)
      nil)))

(defn make-user
  "Creates a user in coll in db with given fields
  (the one specified by *login-field*, :username by default
  and whatever you need) and password"
  [db coll user password]
  (let [salt (secure-rand)]
    (create db coll
      (merge user
        {:token (secure-rand 64)
         :id    (str (UUID/randomUUID))
         :salt  salt
         :hash  (DigestUtils/sha256Hex (str salt *fixed-salt-part* password))}))))

(defn wrap-auth
  "Ring middleware that adds :user if there's a user logged in. Supports session/form-based auth and HTTP Basic auth"
  ([handler] (wrap-auth handler {}))
  ([handler {:keys [db coll] :or {db inmem coll :ringfinger_auth}}]
   (fn [req]
     (let [auth-hdr (get-in req [:headers "authorization"] "")
           cookie-token (get-in req [:cookies "a" :value])
           auth-type (cond cookie-token :form
                           (substring? "Basic" auth-hdr) :basic
                           :else nil)]
       (-> req
           (assoc :user (case auth-type
                          :form (let [user (get-one db coll {:query {:token cookie-token}})]
                                  (if (nil? (:_confirm_key user)) user nil))
                          :basic (let [cr (split (new String (Base64/decodeBase64 (str-drop 6 auth-hdr))) #":")]
                                   (get-user db coll (first cr) (second cr)))
                          nil))
           (assoc :auth-type auth-type)
           handler)))))

(defn make-username-hook
  "Returns a restfinger hook which changes returned owner-field value
  from the user id to the value of *login-field*
  Options: :db, :coll (the ones you use with wrap-auth and auth-routes)
  and owner-field"
  [{:keys [db coll owner-field] :or {db inmem coll :ringfinger_auth owner-field :owner}}]
  (fn [data]
    (assoc data owner-field (*login-field* (get-one db coll {:query {:id (owner-field data)}})))))

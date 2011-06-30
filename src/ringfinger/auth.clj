(ns ringfinger.auth
  (:use ringfinger.db, ringfinger.db.inmem)
  (:require [clojure.contrib.string :as cstr])
  (:import org.apache.commons.codec.digest.DigestUtils,
           org.apache.commons.codec.binary.Base64))

(defn get-user [db coll username password fixed-salt-part]
  (let [user (get-one db coll {:username username})]
    (if (= (:password_hash user) (DigestUtils/sha256Hex (str (:password_salt user) fixed-salt-part password)))
      user
      nil)))

(defn make-user [db coll user password fixed-salt-part]
  (let [salt (str (rand))]
    (create db coll
      (merge user
        {:password_salt salt
         :password_hash (DigestUtils/sha256Hex (str salt fixed-salt-part password))}))))

(defn wrap-auth
  ([handler] (wrap-auth handler {:db inmem, :coll :ringfinger_auth}))
  ([handler options]
   (let [db   (:db options)
         coll (:coll options)]
     (fn [req]
       (let [auth-hdr (get (:headers req) "authorization" "")
             session-username (:username (:session req))]
         (handler (assoc req :user
            (cond (cstr/substring? "Basic" auth-hdr)
                    (let [cr (cstr/split #":" (new String (Base64/decodeBase64 (cstr/drop 6 auth-hdr))))]
                      (get-user db coll (first cr) (second cr)))
                  session-username (get-one db coll {:username session-username})
                  :else nil))))))))

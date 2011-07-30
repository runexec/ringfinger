(ns ringfinger.test.db
  (:use ringfinger.db, ringfinger.db.inmem, clojure.test))

(deftest creation
  (is (= {:test [{:key "value"}]} (create inmem :test {:key "value"}))))

(deftest reading
  (is (= {:key "value"} (get-one inmem :test {:query {:key "value"}}))))

(deftest updating
  (is (= {:test [{:key "updated"}]} (update inmem :test (get-one inmem :test {:query {:key "value"}}) {:key "updated"}))))

(deftest deleting
  (is (= {:test []} (delete inmem :test (get-one inmem :test {:query {:key "updated"}})))))

(defn test-ns-hook [] ; order matters here
  (reset-inmem-db)
  (creation)
  (reading)
  (updating)
  (deleting)
  (reset-inmem-db))
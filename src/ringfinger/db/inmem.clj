(ns ringfinger.db.inmem
  "In-memory data storage FOR TESTING USE ONLY"
  (:use (ringfinger db util)))

(def base (ref {}))

(def inmem (reify Database
  (create [self coll data]
    (dosync
      (ref-set base
        (assoc @base coll (if (false? (coll base))
                              [data]
                              (conj (get @base coll) data))))))
  (get-many [self coll options]
    (sort-maps (filter (make-filter (:query options))
                       (let [a (get @base coll)
                             s (:skip options)
                             l (:limit options)
                             b (if s (drop s a) a)]
                         (if l (take l b) b))) (:sort options)))
  (get-one [self coll options]
    (first (get-many self coll options)))
  (update  [self coll entry data]
    (dosync
      (ref-set base (assoc @base coll (replace {entry (merge entry data)} (get @base coll))))))
  (delete [self coll entry]
    (dosync
      (ref-set base (assoc @base coll (remove (partial = entry) (get @base coll))))))))

(defn reset-inmem-db []
  (dosync (ref-set base {})))

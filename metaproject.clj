(use 'clojure.java.shell)

; Ringfinger's version is defined by git tags
(def version
  (if (= (System/getenv "HAS_JOSH_K_SEAL_OF_APPROVAL") "true")
    (sh "git" "checkout" "master")) ; Travis CI, Y U CHECKOUT REVISION
  (let [out (:out (sh "git" "describe" "--abbrev=0" "--tags"))]
    (.substring out 0 (- (count out) 1)))) ; remove the \n
(def clj-version "1.2.1")
(def ring-version "1.0.0-beta2")

(def fingers ["toolfinger" "secfinger"])
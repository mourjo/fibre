(ns fibre.core-test
  (:require [clojure.test :refer :all]
            [fibre.core :as sut]))

(deftest simple-test
  (sut/start-task-scheduler)
  (dotimes [i 1000]
    (let [x (atom [])
          f1 (sut/in-future #(do (Thread/sleep (rand-int 10))
                              (swap! x conj i)))
          f2 (sut/in-future #(do (Thread/sleep (rand-int 10))
                                 (swap! x conj (inc i))))
          f3 (sut/in-future #(swap! x conj (+ 2 i)))]
      (doseq [f (shuffle [f1 f2 f3])]
        (deref f))
      (is (apply < @x)))))
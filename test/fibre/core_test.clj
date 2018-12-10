(ns fibre.core-test
  (:require [clojure.test :refer :all]
            [fibre.core :as sut]))

(deftest simple-test
  (sut/start-task-scheduler)
  (dotimes [i 100]
    (let [x (atom [])
          f1 (sut/in-future #(do (Thread/sleep (rand-int 100))
                              (swap! x conj i)))
          f2 (sut/in-future #(do (Thread/sleep (rand-int 10))
                              (swap! x conj (inc i))))]
      (doseq [f [f1 f2]]
        (deref f))
      (is (= [i (inc i)]
             @x)))))
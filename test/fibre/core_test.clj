(ns fibre.core-test
  (:require [clojure.test :refer :all]
            [fibre.core :as sut])
  (:import (java.util.concurrent CancellationException)))


(defn start-scheduler
  [t]
  (let [sched-f (sut/start-task-scheduler)]
    (try (t)
         (finally (future-cancel sched-f)))))

(use-fixtures :once start-scheduler)


(deftest simple-test
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


(deftest future-cancel-test
  (doseq [wait-forever [#(Thread/sleep 1000000000)
                        #(loop []
                           (if (Thread/interrupted)
                             (throw (InterruptedException.))
                             (recur)))]]
    (dotimes [_ 1000]
      (let [f1 (sut/in-future wait-forever)
            f2 (sut/in-future #(+ 1 2))]
        (is (future-cancel f1))
        (is (= @f2 3)))

      (let [a (atom [])
            f1 (sut/in-future #(do (wait-forever)
                                   (swap! a conj ::something)))
            f2 (sut/in-future #(swap! a conj ::something))
            f3 (sut/in-future #(+ 1 2))]
        (is (future-cancel f2))
        (is (future-cancel f1))
        (is (= @f3 3))
        (is (= [] @a))
        (is (thrown? CancellationException @f2))
        (is (thrown? CancellationException @f1))))))

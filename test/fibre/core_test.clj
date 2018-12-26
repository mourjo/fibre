(ns fibre.core-test
  (:require [clojure.test :refer :all]
            [fibre.core :as sut]
            [clojure.test.check.clojure-test :refer :all]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop])
  (:import (java.util.concurrent CancellationException ArrayBlockingQueue Executors)))


(defn start-and-stop-scheduler
  [t]
  (try (sut/start-task-scheduler)
       (t)
       (finally (sut/stop-task-scheduler)
                (alter-var-root #'fibre.core/q
                                (fn [& _] (ArrayBlockingQueue. 64 true))))))

(use-fixtures :each start-and-stop-scheduler)


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
        (is (not (future-done? f1)))
        (is (future-cancel f1))
        (is (future-cancelled? f1))
        (is (future-done? f1))
        (is (= @f2 3))
        (is (future-done? f2)))

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


(def future-operations
  {;; :cancel (fn [f] (future-cancel f))
   :cancelled? (fn [f] (future-cancelled? f))
   :done? (fn [f] (future-done? f))
   :deref (fn [f] @f)
   :realized? (fn [f] (realized? f))
   :deref-timeout-1 (fn [f] (deref f 1 ::timeout))
   :deref-timeout-2 (fn [f] (deref f 2 ::timeout))})


(def future-tasks
  {:sleep-10 (fn [data] (Thread/sleep 10) data)
   :sleep-20 (fn [data] (Thread/sleep 20) data)
   :sleep-random-100 (fn [data] (Thread/sleep (rand-int 100)) data)})


(defn run-tasks
  [tasks val]
  (reduce (fn [acc f]
            (when (Thread/interrupted) (throw (InterruptedException.)))
            (f acc))
          val
          (mapv future-tasks tasks)))


(defn launch-future
  [^java.util.concurrent.ExecutorService tp ^Callable f]
  (let [fut ^java.util.concurrent.Future (.submit tp f)]
    (reify
      clojure.lang.IDeref
      (deref [_] (#'clojure.core/deref-future fut))
      clojure.lang.IBlockingDeref
      (deref
          [_ timeout-ms timeout-val]
        (#'clojure.core/deref-future fut timeout-ms timeout-val))
      clojure.lang.IPending
      (isRealized [_] (.isDone fut))
      java.util.concurrent.Future
      (get [_] (.get fut))
      (get [_ timeout unit] (.get fut timeout unit))
      (isCancelled [_] (.isCancelled fut))
      (isDone [_] (.isDone fut))
      (cancel [_ interrupt?] (.cancel fut interrupt?)))))


(defspec future-task-execution
  100
  (let [tp (Executors/newFixedThreadPool 1)]
    (prop/for-all
     [fut-ops (gen/vector (gen/elements (keys future-tasks)) 0 10)
      tasks (gen/vector (gen/elements (keys future-operations)) 0 5)
      op gen/int]
     (let [task-chain #(run-tasks tasks op)
           cf ^java.util.concurrent.Future (launch-future tp task-chain)
           _ (sut/in-future task-chain)
           mf (sut/in-future task-chain)
           _ (sut/in-future task-chain)]

       (doseq [f-opt fut-ops]
         (let [opt (future-tasks f-opt)]
           (try (opt cf)
                (catch Throwable _))
           (try (opt mf)
                (catch Throwable _))))

       (= (try @cf
               (catch Throwable t (class t)))
          (try @mf
               (catch Throwable t (class t))))))))

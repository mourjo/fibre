(ns fibre.core
  (:gen-class)
  (:import (java.util.concurrent TimeUnit TimeoutException Future ArrayBlockingQueue)
           (clojure.lang IPending IBlockingDeref IDeref)))


(def q (ArrayBlockingQueue. 64 true))

(defn submit-task
  [task-fn]
  (let [p (promise)
        f (reify
            IDeref
            (deref [_] (-> p deref deref))

            IBlockingDeref
            (deref
                [_ timeout-ms timeout-val]
              (if (realized? p)
                (deref @p timeout-ms timeout-val)
                (let [t (System/currentTimeMillis)
                      x (deref p timeout-ms ::timeout)]
                  (if (= ::timeout x)
                    timeout-val
                    (deref x
                           (- timeout-ms (- (System/currentTimeMillis) t))
                           timeout-val)))))

            IPending
            (isRealized [_] (if (realized? p) (.isDone @p) false))

            Future
            (get [_] (-> p deref deref))
            (get [_ timeout unit]
              (if (realized? p)
                (.get @p timeout unit)
                (let [t (System/currentTimeMillis)
                      timeout-ms (.convert TimeUnit/MILLISECONDS timeout unit)
                      x (deref p timeout-ms ::timeout)]
                  (if (= ::timeout x)
                    (throw (TimeoutException.))
                    (.get x
                          (- timeout-ms (- (System/currentTimeMillis) t))
                          TimeUnit/MILLISECONDS)))))
            (isCancelled [_] (if (realized? p)
                               (.isCancelled @p)
                               false))
            (isDone [_] (if (realized? p)
                          (.isDone @p)
                          false))
            (cancel [_ interrupt?] (if (realized? p)
                                     (.cancel @p interrupt?)
                                     false)))] ;; @TODO implement cancellation
    (.put q {:pr p :task-fn task-fn})
    f))


(defn task-scheduler
  []
  (future
    (loop []
      (let [{:keys [pr task-fn]} (.take q)
            f (future (task-fn))]
        (deliver pr f)
        @f
        (recur)))))



(defn start-task-scheduler
  []
  (task-scheduler))


(defn in-future
  [f]
  (submit-task f))



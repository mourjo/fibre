(ns fibre.core
  (:gen-class)
  (:import (java.util.concurrent TimeUnit TimeoutException Future ArrayBlockingQueue CancellationException)
           (clojure.lang IPending IBlockingDeref IDeref)))

(defonce q (ArrayBlockingQueue. 64 true))
(defonce scheduler nil)

(defn submit-task
  [task-fn]
  (let [p (promise)
        f (reify
            IDeref
            (deref [_] (if (and (realized? p)
                                (= ::cancelled @p))
                         (throw (CancellationException.))
                         (-> p deref deref)))

            IBlockingDeref
            (deref
              [_ timeout-ms timeout-val]
              (cond
                (and (realized? p)
                     (= ::cancelled @p))
                (throw (CancellationException.))

                (realized? p)
                (deref @p timeout-ms timeout-val)

                :else
                (let [t (System/currentTimeMillis)
                      x (deref p timeout-ms ::timeout)]
                  (if (= ::timeout x)
                    timeout-val
                    (deref x
                           (->> t
                                (- (System/currentTimeMillis))
                                (max 0)
                                (- timeout-ms))
                           timeout-val)))))

            IPending
            (isRealized [_] (if (realized? p)
                              (try (.isDone @p)
                                   (catch IllegalArgumentException _ true))
                              false))

            Future
            (get [_] (if (and (realized? p)
                              (= ::cancelled @p))
                       (throw (CancellationException.))
                       (-> p deref deref)))
            (get [_ timeout unit]
              (cond
                (and (realized? p)
                     (= ::cancelled @p))
                (throw (CancellationException.))

                (realized? p)
                (.get @p timeout unit)

                :else
                (let [t (System/currentTimeMillis)
                      timeout-ms (.convert TimeUnit/MILLISECONDS timeout unit)
                      x (deref p timeout-ms ::timeout)]
                  (if (= ::timeout x)
                    (throw (TimeoutException.))
                    (.get x
                          (->> t
                               (- (System/currentTimeMillis))
                               (max 0)
                               (- timeout-ms))
                          TimeUnit/MILLISECONDS)))))
            (isCancelled [_] (if (realized? p)
                               (try (.isCancelled @p)
                                    (catch IllegalArgumentException _ true))
                               false))
            (isDone [_] (if (realized? p)
                          (try (.isDone @p)
                               (catch IllegalArgumentException _ true))
                          false))
            (cancel [_ interrupt?] (cond
                                     (realized? p)
                                     (try (.cancel @p interrupt?)
                                          (catch IllegalArgumentException _ true))

                                     (deliver p ::cancelled)
                                     true

                                     :else
                                     (.cancel @p interrupt?))))]
    (.put q {:pr p :task-fn task-fn})
    f))


(defn task-scheduler
  []
  (future
    (loop []
      (let [{:keys [pr task-fn]} (.take q)]
        (when-not (realized? pr)
          (let [f (future (task-fn))]
            (if-not (deliver pr f)
              (future-cancel f)
              (try @f
                   (catch Throwable _)))))
        (when-not (Thread/interrupted)
          (recur))))))



(defn start-task-scheduler
  []
  (let [f (task-scheduler)]
    (alter-var-root #'scheduler (constantly f))))


(defn stop-task-scheduler
  []
  (try (future-cancel scheduler) (catch Throwable _))
  (alter-var-root #'scheduler (constantly nil)))


(defn in-future
  [f]
  (submit-task f))

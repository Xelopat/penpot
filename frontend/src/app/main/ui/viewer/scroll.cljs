;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.viewer.scroll
  "Animated scroll of the viewer, used by the scroll-to interaction.")

(def ^:private easing-curves
  {:linear      [0 0 1 1]
   :ease        [0.25 0.1 0.25 1]
   :ease-in     [0.42 0 1 1]
   :ease-out    [0 0 0.58 1]
   :ease-in-out [0.42 0 0.58 1]})

(defn cubic-bezier
  "Easing function equivalent to the CSS `cubic-bezier(x1, y1, x2, y2)`."
  [[x1 y1 x2 y2]]
  (let [bezier (fn [a b t]
                 (let [u (- 1 t)]
                   (+ (* 3 u u t a) (* 3 u t t b) (* t t t))))]
    (fn [x]
      (cond
        (<= x 0) 0
        (>= x 1) 1
        :else
        (loop [lo 0 hi 1 i 0]
          (let [t (/ (+ lo hi) 2)]
            (if (> i 24)
              (bezier y1 y2 t)
              (if (< (bezier x1 x2 t) x)
                (recur t hi (inc i))
                (recur lo t (inc i))))))))))

(defn easing-fn
  [easing]
  (cubic-bezier (get easing-curves easing (:ease-in-out easing-curves))))

;; Animation frame in progress for each scrolled element
(defonce ^:private current-frames (js/WeakMap.))

(defn- cancel!
  [^js node]
  (when-let [id (.get current-frames node)]
    (js/cancelAnimationFrame id)
    (.delete current-frames node)))

(defn animate-scroll!
  "Scroll `node` to `left`/`top` following `animation` (its duration and
  easing). Without animation the scroll is immediate. A new scroll cancels
  the one in progress on the same element."
  [^js node left top animation]
  (cancel! node)
  (let [max-top  (max 0 (- (.-scrollHeight node) (.-clientHeight node)))
        max-left (max 0 (- (.-scrollWidth node) (.-clientWidth node)))
        top      (-> top (max 0) (min max-top))
        left     (-> left (max 0) (min max-left))
        duration (or (:duration animation) 0)]
    (if (<= duration 0)
      (do (set! (.-scrollTop node) top)
          (set! (.-scrollLeft node) left))
      (let [from-top  (.-scrollTop node)
            from-left (.-scrollLeft node)
            ease      (easing-fn (:easing animation))
            start     (js/performance.now)
            step      (fn step [now]
                        (let [progress (min 1 (/ (- now start) duration))
                              k        (ease progress)]
                          (set! (.-scrollTop node) (+ from-top (* k (- top from-top))))
                          (set! (.-scrollLeft node) (+ from-left (* k (- left from-left))))
                          (if (< progress 1)
                            (.set current-frames node (js/requestAnimationFrame step))
                            (.delete current-frames node))))]
        (.set current-frames node (js/requestAnimationFrame step))))))

(defn scrolling?
  "Check if an animated scroll of `node` is in progress."
  [^js node]
  (.has current-frames node))

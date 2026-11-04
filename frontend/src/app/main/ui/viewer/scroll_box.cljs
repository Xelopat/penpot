;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.viewer.scroll-box
  "Boards whose content scrolls in the viewer (overflow scrolling).

  The content of such a board is drawn in its own scrollable element laid
  over the board, while the board background stays in the main drawing,
  so the content moves and the background does not. Scroll boxes are drawn
  above the layers that do not scroll with the page and below the fixed
  ones."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.types.shape :as cts]
   [app.main.ui.viewer.scroll :as vsc]
   [app.main.ui.viewer.shapes :as shapes]
   [goog.events :as events]
   [rumext.v2 :as mf]))

(def ^:private drag-threshold 4)
(def ^:private snap-delay 140)
(def ^:private snap-animation {:duration 250 :easing :ease-out})

(defn scroll-board?
  [shape]
  (and (cfh/frame-shape? shape)
       (contains? cts/scroll-overflow-types (:scroll-overflow shape))
       (not (:hidden shape))))

(defn scroll-board-ids
  "Outermost visible nested boards inside `frame-id` whose content scrolls.
  Boards inside a scrolling board scroll with it."
  [objects frame-id]
  (let [candidates (into #{}
                         (filter #(scroll-board? (get objects %)))
                         (cfh/get-children-ids objects frame-id))]
    (into #{}
          (remove (fn [id]
                    (let [parent-ids (cfh/get-parent-ids objects id)]
                      (or (some candidates parent-ids)
                          (some #(:hidden (get objects %)) parent-ids)))))
          candidates)))

(defn scroll-content-ids
  "Ids drawn inside the scroll boxes instead of the main drawing."
  [objects board-ids]
  (into #{} (mapcat #(cfh/get-children-ids objects %)) board-ids))

(defn prepare-board
  "The board as drawn in its scroll box: only its content, without clip.
  Its background and timed interactions stay with the main drawing."
  [board]
  (-> board
      (assoc :fills [] :strokes [] :shadow [] :show-content true)
      (dissoc :blur)
      (update :interactions
              (fn [interactions]
                (not-empty (filterv #(not= :after-delay (:event-type %)) interactions))))))

(defn content-size
  "Size of the scrolled content: the board, extended to its farthest child."
  [objects board]
  (let [{:keys [x y width height]} (:selrect board)
        rects (->> (cfh/get-children-ids objects (:id board))
                   (keep (d/getf objects))
                   (map :selrect))]
    [(reduce max width (map #(- (:x2 %) x) rects))
     (reduce max height (map #(- (:y2 %) y) rects))]))

(defn snap-points
  "Scroll positions (in board units) that align a child with the board."
  [objects board]
  (let [{:keys [x y]} (:selrect board)
        children (keep (d/getf objects) (:shapes board))]
    {:x (into (sorted-set) (map #(max 0 (- (-> % :selrect :x) x))) children)
     :y (into (sorted-set) (map #(max 0 (- (-> % :selrect :y) y))) children)}))

(defn- nearest
  [points current]
  (when (seq points)
    (apply min-key #(abs (- % current)) points)))

(mf/defc scroll-box*
  [{:keys [board objects all-objects scale]}]
  (let [node-ref    (mf/use-ref nil)
        drag-ref    (mf/use-ref nil)
        snap-ref    (mf/use-ref nil)

        {:keys [x y width height]} (:selrect board)
        overflow    (:scroll-overflow board)
        horizontal? (contains? #{:horizontal :both} overflow)
        vertical?   (contains? #{:vertical :both} overflow)
        snap?       (true? (:scroll-snap board))

        [content-width content-height]
        (mf/with-memo [objects board]
          (content-size objects board))

        points
        (mf/with-memo [objects board]
          (snap-points objects board))

        wrapper
        (mf/with-memo [objects all-objects]
          (shapes/frame-container-factory objects all-objects))

        ;; Mouse drag scrolls, like a swipe. It starts only on areas without
        ;; their own click interactions and after a small movement.
        on-pointer-down
        (mf/use-fn
         (fn [event]
           (let [native (.-nativeEvent ^js event)
                 node   (mf/ref-val node-ref)]
             (when (and (= "mouse" (.-pointerType native))
                        (zero? (.-button native)))
               (mf/set-ref-val! drag-ref {:x (.-clientX native)
                                          :y (.-clientY native)
                                          :left (.-scrollLeft ^js node)
                                          :top (.-scrollTop ^js node)
                                          :pointer (.-pointerId native)
                                          :moved false})))))

        on-pointer-move
        (mf/use-fn
         (mf/deps horizontal? vertical?)
         (fn [event]
           (when-let [drag (mf/ref-val drag-ref)]
             (let [native (.-nativeEvent ^js event)
                   node   (mf/ref-val node-ref)
                   dx     (- (.-clientX native) (:x drag))
                   dy     (- (.-clientY native) (:y drag))
                   moved  (or (:moved drag)
                              (> (max (abs dx) (abs dy)) drag-threshold))]
               (when moved
                 (when-not (:moved drag)
                   (.setPointerCapture ^js node (:pointer drag))
                   (mf/set-ref-val! drag-ref (assoc drag :moved true)))
                 (when horizontal? (set! (.-scrollLeft ^js node) (- (:left drag) dx)))
                 (when vertical? (set! (.-scrollTop ^js node) (- (:top drag) dy))))))))

        snap-later
        (mf/use-fn
         (fn []
           (when-let [snap (mf/ref-val snap-ref)]
             (snap))))

        on-pointer-up
        (mf/use-fn
         (mf/deps snap-later)
         (fn [_]
           (let [drag (mf/ref-val drag-ref)
                 node (mf/ref-val node-ref)]
             (mf/set-ref-val! drag-ref nil)
             ;; A drag that scrolled is not a click on the layer below
             (when (:moved drag)
               (.addEventListener ^js node "click"
                                  (fn [^js e]
                                    (.stopPropagation e)
                                    (.preventDefault e))
                                  #js {:capture true :once true})
               (js/setTimeout snap-later 0)))))]

    ;; Snap to the nearest child once the scroll stops: on `scrollend`
    ;; (after the finger is lifted and the inertia ends) where available
    (mf/with-effect [snap? points scale horizontal? vertical?]
      (when snap?
        (let [node   (mf/ref-val node-ref)
              timer  (volatile! nil)
              ended? (some? (.-onscrollend ^js js/window))
              snap   (fn []
                       (when-not (or (vsc/scrolling? node)
                                     (:moved (mf/ref-val drag-ref)))
                         (let [left   (.-scrollLeft ^js node)
                               top    (.-scrollTop ^js node)
                               to-x   (if horizontal?
                                        (some-> (nearest (:x points) (/ left scale)) (* scale))
                                        left)
                               to-y   (if vertical?
                                        (some-> (nearest (:y points) (/ top scale)) (* scale))
                                        top)
                               to-x   (or to-x left)
                               to-y   (or to-y top)]
                           (when (or (> (abs (- to-x left)) 1) (> (abs (- to-y top)) 1))
                             (vsc/animate-scroll! node to-x to-y snap-animation)))))
              on-scroll (fn [_]
                          (some-> @timer js/clearTimeout)
                          (vreset! timer (js/setTimeout snap snap-delay)))
              key    (if ended?
                       (events/listen node "scrollend" snap)
                       (events/listen node "scroll" on-scroll))]
          ;; Mouse drags set the scroll directly and end without inertia,
          ;; so the pointer up snaps through this ref
          (mf/set-ref-val! snap-ref snap)
          (fn []
            (events/unlistenByKey key)
            (mf/set-ref-val! snap-ref nil)
            (some-> @timer js/clearTimeout)))))

    [:div {:ref node-ref
           :id (dm/str "scroll-box-" (:id board))
           :class "viewer-scroll-box"
           :on-pointer-down on-pointer-down
           :on-pointer-move on-pointer-move
           :on-pointer-up on-pointer-up
           :on-pointer-cancel on-pointer-up
           :style {:position "absolute"
                   :left (* x scale)
                   :top (* y scale)
                   :width (* width scale)
                   :height (* height scale)
                   :overflow-x (if horizontal? "auto" "hidden")
                   :overflow-y (if vertical? "auto" "hidden")
                   :overscroll-behavior-x "contain"
                   :scrollbar-width "none"}}
     [:svg {:view-box (dm/str x " " y " " content-width " " content-height)
            :width (* content-width scale)
            :height (* content-height scale)
            :version "1.1"
            :xmlnsXlink "http://www.w3.org/1999/xlink"
            :xmlns "http://www.w3.org/2000/svg"
            :fill "none"
            :style {:display "block"}}
      [:& wrapper {:shape board}]]]))

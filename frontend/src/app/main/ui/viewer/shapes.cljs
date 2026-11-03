;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.viewer.shapes
  "The main container for a frame in viewer mode"
  (:require
   [app.common.data :as d]
   [app.common.files.helpers :as cfh]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.shapes.bounds :as gsb]
   [app.common.types.shape.interactions :as ctsi]
   [app.common.uuid :as uuid]
   [app.main.data.viewer :as dv]
   [app.main.refs :as refs]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.main.ui.shapes.bool :as bool]
   [app.main.ui.shapes.circle :as circle]
   [app.main.ui.shapes.frame :as frame]
   [app.main.ui.shapes.group :as group]
   [app.main.ui.shapes.image :as image]
   [app.main.ui.shapes.path :as path]
   [app.main.ui.shapes.rect :as rect]
   [app.main.ui.shapes.shape :refer [shape-container]]
   [app.main.ui.shapes.svg-raw :as svg-raw]
   [app.main.ui.shapes.text :as text]
   [app.main.ui.viewer.scroll :as vsc]
   [app.util.dom :as dom]
   [app.util.object :as obj]
   [app.util.timers :as tm]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def base-frame-ctx (mf/create-context nil))
(def frame-offset-ctx (mf/create-context nil))

(def ^:private ref:viewer-show-interactions
  (l/derived :show-interactions refs/viewer-local))

(defn- find-relative-to-base-frame
  [shape objects overlays-ids base-frame]
  (cond
    (cfh/frame-shape? shape) shape
    (or (empty? overlays-ids) (nil? shape) (cfh/root? shape)) base-frame
    :else (find-relative-to-base-frame (cfh/get-parent objects (:id shape)) objects overlays-ids base-frame)))

(defn- ignore-frame-shape
  [shape objects manual?]
  (let [shape (cond-> shape ;; When the the interaction is not manual and its origin is a frame,
                ;; we need to ignore it on all the find-frame calculations
                (and (:frame-id shape) (not manual?))
                (assoc :type :rect))
        objects (assoc objects (:id shape) shape)]
    [shape objects]))

(defn- find-overlay-of
  "The open overlay that contains the shape `id`, if any."
  [id objects overlays]
  (when (and (some? id) (seq overlays))
    (let [ids (into #{id} (cfh/get-parent-ids objects id))]
      (d/seek #(contains? ids (:id %)) (rseq (vec overlays))))))

(defn- get-viewport-rect
  "Visible area of the base frame, in base frame coordinates, as it is laid
  out before any scroll. Overlays rendered fixed at that place stay in the
  visible area of the screen while the page scrolls below them."
  [base-frame]
  (let [{:keys [width height]} (:selrect base-frame)
        fallback  {:x 0 :y 0 :width width :height height}
        section   (dom/get-element "viewer-section")
        container (dom/get-element "viewer-base-viewport")
        zoom      (get-in @st/state [:viewer-local :zoom] 1)]
    (if (or (nil? section) (nil? container))
      fallback
      (let [srect (dom/get-bounding-rect section)
            crect (dom/get-bounding-rect container)
            ;; Container position with the section scrolled to its origin
            left  (+ (:left crect) (.-scrollLeft ^js section))
            top   (+ (:top crect) (.-scrollTop ^js section))
            x1    (max 0 (/ (- (:left srect) left) zoom))
            y1    (max 0 (/ (- (:top srect) top) zoom))
            x2    (min width (/ (- (+ (:left srect) (.-clientWidth ^js section)) left) zoom))
            y2    (min height (/ (- (+ (:top srect) (.-clientHeight ^js section)) top) zoom))]
        (if (or (<= x2 x1) (<= y2 y1))
          fallback
          {:x x1 :y y1 :width (- x2 x1) :height (- y2 y1)})))))

(defn- calc-overlay
  "Where and how to show the overlay of an `open-overlay`,
  `toggle-overlay` or `swap-overlay` interaction."
  [interaction shape base-frame frame-offset objects overlays]
  (let [manual?                (= :manual (:overlay-pos-type interaction))
        viewport?              (ctsi/relative-to-viewport? interaction)
        source-overlay         (find-overlay-of (:id shape) objects overlays)
        [shape objects]        (ignore-frame-shape shape objects manual?)
        dest-frame-id          (:destination interaction)
        dest-frame             (get objects dest-frame-id)
        relative-to-id         (if manual?
                                 (if (= (:type shape) :frame) ;; manual interactions are always from "self"
                                   (:frame-id shape)
                                   (:id shape))
                                 (:position-relative-to interaction))
        relative-to-shape      (or (get objects relative-to-id) base-frame)
        overlays-ids           (set (map :id overlays))
        relative-to-base-frame (find-relative-to-base-frame relative-to-shape objects overlays-ids base-frame)

        [position snap-to]
        (if (and viewport? (some? dest-frame))
          (ctsi/calc-overlay-viewport-position interaction
                                               (gsb/get-object-bounds objects dest-frame)
                                               (get-viewport-rect base-frame))
          (ctsi/calc-overlay-position interaction
                                      shape
                                      objects
                                      relative-to-shape
                                      relative-to-base-frame
                                      dest-frame
                                      frame-offset))

        ;; An overlay placed against something inside an overlay that is
        ;; fixed on screen must stay fixed with it
        inside-fixed-overlay?
        (and (:fixed-source? source-overlay)
             (some? (find-overlay-of relative-to-id objects [source-overlay])))]

    {:dest-frame-id       dest-frame-id
     :position            position
     :snap-to             snap-to
     :fixed?              (boolean (or viewport?
                                       (cfh/fixed? objects relative-to-id)
                                       inside-fixed-overlay?))
     :source-overlay      source-overlay
     :close-click-outside (:close-click-outside interaction)
     :background-overlay  (:background-overlay interaction)
     :animation           (:animation interaction)
     :opts                {:anchor (cond viewport? :viewport
                                         manual?   (:id shape)
                                         :else     relative-to-id)
                           :pos-type (:overlay-pos-type interaction)
                           :source-id (:id shape)
                           :hover-source? (contains? #{:mouse-over :mouse-enter}
                                                     (:event-type interaction))}}))

(defn- swap-overlay-params
  "Overlays replaced by a `swap-overlay` interaction and where the new one
  goes. Inside an overlay, the overlay holding the trigger is replaced;
  outside, the overlays opened against the same reference (tabs over the
  same anchor). An overlay with automatic position takes the place of the
  one it replaces."
  [overlay interaction base-frame overlays objects]
  (let [{:keys [source-overlay opts]} overlay
        auto?    (and (nil? (:position-relative-to interaction))
                      (not (ctsi/relative-to-viewport? interaction))
                      (not= :manual (:overlay-pos-type interaction)))
        in-place (when (and auto? (some? source-overlay)) source-overlay)
        replace  (if (some? source-overlay)
                   [(:id source-overlay)]
                   (->> overlays
                        (filter #(= (:anchor %) (:anchor opts)))
                        (map :id)))]
    (if (nil? in-place)
      (assoc overlay :replace-ids replace)
      (let [dest-frame (get objects (:destination interaction))
            [position snap-to]
            (if (and (= :viewport (:anchor in-place)) (some? dest-frame))
              (ctsi/calc-overlay-viewport-position
               {:overlay-pos-type (:pos-type in-place)}
               (gsb/get-object-bounds objects dest-frame)
               (get-viewport-rect base-frame))
              [(:position in-place) (:snap-to in-place)])]
        (assoc overlay
               :replace-ids replace
               :position position
               :snap-to snap-to
               :fixed? (:fixed-source? in-place)
               :opts (assoc opts
                            :anchor (:anchor in-place)
                            :pos-type (:pos-type in-place)))))))

(defn- scroll-to!
  "Scroll the viewer so the destination layer is at the top of the screen.
  When the layer belongs to another screen, navigate there first."
  [interaction]
  (let [state         @st/state
        qparams       (get-in state [:route :query-params])
        page-id       (some-> (:page-id qparams) uuid/parse)
        index         (some-> (rt/get-query-param qparams :index) parse-long)
        page          (get-in state [:viewer :pages page-id])
        objects       (:objects page)
        dest          (get objects (:destination interaction))
        frame         (some->> (:id dest) (cfh/get-root-frame objects))
        current       (get (:frames page) index)
        zoom          (get-in state [:viewer-local :zoom] 1)
        offset        (or (:scroll-offset interaction) (gpt/point 0 0))
        animation     (:animation interaction)]
    (when (and (some? dest) (some? frame))
      (let [bounds (gsb/get-object-bounds objects frame)
            rect   (:selrect dest)
            dx     (* (+ (- (:x rect) (:x bounds)) (:x offset)) zoom)
            dy     (* (+ (- (:y rect) (:y bounds)) (:y offset)) zoom)]
        (if (= (:id frame) (:id current))
          (let [section   (dom/get-element "viewer-section")
                container (dom/get-element "viewer-base-viewport")]
            (when (and section container)
              (let [srect (dom/get-bounding-rect section)
                    crect (dom/get-bounding-rect container)]
                (vsc/animate-scroll! section
                                     (+ (.-scrollLeft ^js section) (- (:left crect) (:left srect)) dx)
                                     (+ (.-scrollTop ^js section) (- (:top crect) (:top srect)) dy)
                                     animation))))
          (when (some #(= (:id %) (:id frame)) (:frames page))
            (st/emit! (dv/set-nav-scroll (max 0 dy))
                      (dv/go-to-frame (:id frame)
                                      (when animation
                                        {:animation-type :dissolve
                                         :duration (:duration animation)
                                         :easing (:easing animation)})))))))))

(defn- activate-interaction
  [interaction shape base-frame frame-offset objects overlays]
  (case (:action-type interaction)
    :navigate
    (when-let [frame-id (:destination interaction)]
      (let [viewer-section (dom/get-element "viewer-section")
            scroll (if (:preserve-scroll interaction)
                     (dom/get-scroll-pos viewer-section)
                     0)]
        (st/emit! (dv/set-nav-scroll scroll)
                  (dv/go-to-frame frame-id (:animation interaction)))))

    :open-overlay
    (let [{:keys [dest-frame-id position snap-to close-click-outside
                  background-overlay animation fixed? opts]}
          (calc-overlay interaction shape base-frame frame-offset objects overlays)]
      (when dest-frame-id
        (st/emit! (dv/open-overlay dest-frame-id
                                   position
                                   snap-to
                                   close-click-outside
                                   background-overlay
                                   animation
                                   fixed?
                                   opts))))

    :toggle-overlay
    (let [{:keys [dest-frame-id position snap-to close-click-outside
                  background-overlay animation fixed? opts]}
          (calc-overlay interaction shape base-frame frame-offset objects overlays)]
      (when dest-frame-id
        (st/emit! (dv/toggle-overlay dest-frame-id
                                     position
                                     snap-to
                                     close-click-outside
                                     background-overlay
                                     animation
                                     fixed?
                                     opts))))

    :swap-overlay
    (let [{:keys [dest-frame-id position snap-to close-click-outside
                  background-overlay animation fixed? opts replace-ids]}
          (-> (calc-overlay interaction shape base-frame frame-offset objects overlays)
              (swap-overlay-params interaction base-frame overlays objects))]
      (when dest-frame-id
        (st/emit! (dv/swap-overlay replace-ids
                                   dest-frame-id
                                   position
                                   snap-to
                                   close-click-outside
                                   background-overlay
                                   animation
                                   fixed?
                                   opts))))

    :close-overlay
    (let [dest-frame-id (or (:destination interaction)
                            (if (and (= (:type shape) :frame)
                                     (some #(= (:id %) (:id shape)) overlays))
                              (:id shape)
                              (:frame-id shape)))]
      (st/emit! (dv/close-overlay dest-frame-id (:animation interaction))))

    :scroll-to
    (scroll-to! interaction)

    :prev-screen
    (st/emit! (rt/nav-back-local))

    :open-url
    (st/emit! (dom/open-new-window (:url interaction)))

    nil))

;; Perform the opposite action of an interaction, if possible
(defn- deactivate-interaction
  [interaction shape base-frame frame-offset objects overlays]
  (case (:action-type interaction)
    :open-overlay
    (let [frame-id (or (:destination interaction)
                       (if (= (:type shape) :frame)
                         (:id shape)
                         (:frame-id shape)))]
      (st/emit! (dv/close-overlay frame-id
                                  (some-> (:animation interaction) ctsi/invert-direction))))

    :toggle-overlay
    (let [{:keys [dest-frame-id position snap-to close-click-outside
                  background-overlay animation fixed? opts]}
          (calc-overlay interaction shape base-frame frame-offset objects overlays)]
      (when dest-frame-id
        (st/emit! (dv/toggle-overlay dest-frame-id
                                     position
                                     snap-to
                                     close-click-outside
                                     background-overlay
                                     animation
                                     fixed?
                                     opts))))

    :close-overlay
    (let [{:keys [dest-frame-id position snap-to close-click-outside
                  background-overlay animation fixed? opts]}
          (calc-overlay (assoc interaction
                               :action-type :open-overlay
                               :overlay-pos-type (or (:overlay-pos-type interaction) :center))
                        shape base-frame frame-offset objects overlays)]
      (when dest-frame-id
        (st/emit! (dv/open-overlay dest-frame-id
                                   position
                                   snap-to
                                   close-click-outside
                                   background-overlay
                                   animation
                                   fixed?
                                   opts))))
    nil))

(defn- on-pointer-down
  [event shape base-frame frame-offset objects overlays]
  (let [interactions (->> (:interactions shape)
                          (filter #(or (= (:event-type %) :click)
                                       (= (:event-type %) :mouse-press))))]
    (when (seq interactions)
      (dom/stop-propagation event)
      (doseq [interaction interactions]
        (activate-interaction interaction shape base-frame frame-offset objects overlays)))))

(defn- on-pointer-up
  [event shape base-frame frame-offset objects overlays]
  (let [interactions (->> (:interactions shape)
                          (filter #(= (:event-type %) :mouse-press)))]
    (when (seq interactions)
      (dom/stop-propagation event)
      (doseq [interaction interactions]
        (deactivate-interaction interaction shape base-frame frame-offset objects overlays)))))

(defn- on-pointer-enter
  [event shape base-frame frame-offset objects overlays]
  (let [interactions (->> (:interactions shape)
                          (filter #(or (= (:event-type %) :mouse-enter)
                                       (= (:event-type %) :mouse-over))))]
    (when (seq interactions)
      (dom/stop-propagation event)
      (doseq [interaction interactions]
        (activate-interaction interaction shape base-frame frame-offset objects overlays)))))

(defn- hover-overlay-node
  "Rendered overlay opened by a hover interaction, when the pointer just
  moved into it."
  [event interaction]
  (when (contains? #{:open-overlay :toggle-overlay} (:action-type interaction))
    (let [related (.-relatedTarget ^js (dom/event->browser-event event))
          node    (some->> (:destination interaction) (str "overlay-") dom/get-element)]
      (when (and (some? related) (some? node) (.contains ^js node related))
        node))))

(defn- on-pointer-leave
  [event shape base-frame frame-offset objects overlays]
  (let [interactions     (->> (:interactions shape)
                              (filter #(= (:event-type %) :mouse-leave)))
        interactions-inv (->> (:interactions shape)
                              (filter #(= (:event-type %) :mouse-over)))
        trigger-node     (dom/get-current-target event)]
    (when (or (seq interactions) (seq interactions-inv))
      (dom/stop-propagation event)
      (doseq [interaction interactions]
        (activate-interaction interaction shape base-frame frame-offset objects overlays))
      (doseq [interaction interactions-inv]
        (let [deactivate #(deactivate-interaction interaction shape base-frame frame-offset objects overlays)]
          (if-let [overlay-node (hover-overlay-node event interaction)]
            ;; The pointer moved into the overlay this hover opened (for
            ;; example a hover state that covers the trigger): keep it open
            ;; until the pointer leaves both the overlay and the trigger.
            (.addEventListener ^js overlay-node "pointerleave"
                               (fn [^js leave-event]
                                 (let [related (.-relatedTarget leave-event)]
                                   (when-not (and (some? related)
                                                  (some? trigger-node)
                                                  (.contains ^js trigger-node related))
                                     (deactivate))))
                               #js {:once true})
            (deactivate)))))))

(defn- on-load
  [shape base-frame frame-offset objects overlays]
  (let [interactions (->> (:interactions shape)
                          (filter #(= (:event-type %) :after-delay)))]
    (loop [interactions (seq interactions)
           sems []]
      (if-let [interaction (first interactions)]
        (let [sem (tm/schedule (:delay interaction)
                               #(activate-interaction interaction shape base-frame frame-offset objects overlays))]
          (recur (next interactions)
                 (conj sems sem)))
        sems))))

(mf/defc interaction
  [{:keys [shape interactions show-interactions]}]
  (let [{:keys [x y width height]} (:selrect shape)]
    (when-not (empty? interactions)
      [:rect {:x (- x 1)
              :y (- y 1)
              :width (+ width 2)
              :height (+ height 2)
              :fill "var(--color-accent-tertiary)"
              :stroke "var(--color-accent-tertiary)"
              :stroke-width (if show-interactions 1 0)
              :fill-opacity (if show-interactions 0.2 0)
              :pointer-events "none"
              :transform (gsh/transform-str shape)}])))

;; --- WASM viewer hotspots ---
;; In WASM viewer mode the frame pixels come from a WASM snapshot, so we don't
;; render the SVG visuals at all. We only render the actionable areas (hotspots)
;; on top of the image: a transparent hit/highlight rect per interactive shape,
;; wired to the same interaction handlers as the regular SVG tree.

(mf/defc hotspot*
  [{:keys [shape all-objects]}]
  (let [base-frame   (mf/use-ctx base-frame-ctx)
        frame-offset (mf/use-ctx frame-offset-ctx)
        show-interactions (mf/deref ref:viewer-show-interactions)
        overlays     (mf/deref refs/viewer-overlays)
        interactions (:interactions shape)
        {:keys [x y width height]} (:selrect shape)

        on-pd (mf/use-fn (mf/deps shape base-frame frame-offset all-objects overlays)
                         #(on-pointer-down % shape base-frame frame-offset all-objects overlays))
        on-pu (mf/use-fn (mf/deps shape base-frame frame-offset all-objects overlays)
                         #(on-pointer-up % shape base-frame frame-offset all-objects overlays))
        on-pe (mf/use-fn (mf/deps shape base-frame frame-offset all-objects overlays)
                         #(on-pointer-enter % shape base-frame frame-offset all-objects overlays))
        on-pl (mf/use-fn (mf/deps shape base-frame frame-offset all-objects overlays)
                         #(on-pointer-leave % shape base-frame frame-offset all-objects overlays))]

    (mf/with-effect []
      (let [sems (on-load shape base-frame frame-offset all-objects overlays)]
        (partial run! tm/dispose! sems)))

    [:g {:style {:cursor (when (ctsi/actionable? interactions) "pointer")}
         :on-pointer-down on-pd
         :on-pointer-up on-pu
         :on-pointer-enter on-pe
         :on-pointer-leave on-pl}
     [:rect {:x (- x 1)
             :y (- y 1)
             :width (+ width 2)
             :height (+ height 2)
             :fill "var(--color-accent-tertiary)"
             :stroke "var(--color-accent-tertiary)"
             :stroke-width (if show-interactions 1 0)
             :fill-opacity (if show-interactions 0.2 0)
             ;; This rect is the only hit target, so it must always capture
             ;; pointer events even when fully transparent.
             :pointer-events "all"
             :transform (gsh/transform-str shape)}]]))

(mf/defc frame-hotspots*
  "Renders interaction hotspots for a frame subtree (WASM viewer mode).
  `objects` must be the prepared (vbox-space) objects and `frame` the prepared
  frame; only shapes with interactions produce a hotspot.

  Optional `shape-filter` is a predicate that receives the shape id and returns
  true when it should be included (used to split fixed-scroll vs normal layers)."
  [{:keys [objects all-objects shape-filter frame]}]
  (let [all-objects (or all-objects objects)
        frame-id    (:id frame)
        ids         (cond->> (cons frame-id (cfh/get-children-ids objects frame-id))
                      shape-filter (filter shape-filter))
        hotspots    (->> ids
                         (keep #(get objects %))
                         (filter (fn [s] (and (not (:hidden s))
                                              (seq (:interactions s))))))]
    [:g {}
     (for [shape hotspots]
       [:> hotspot* {:key (str (:id shape))
                     :shape shape
                     :all-objects all-objects}])]))


;; TODO: use-memo use-fn

(defn generic-wrapper-factory
  "Wrap some svg shape and add interaction controls"
  [component]
  (mf/fnc generic-wrapper
    {::mf/wrap-props false}
    [props]
    (let [shape              (unchecked-get props "shape")
          childs             (unchecked-get props "childs")
          frame              (unchecked-get props "frame")
          objects            (unchecked-get props "objects")
          all-objects        (or (unchecked-get props "all-objects") objects)
          base-frame         (mf/use-ctx base-frame-ctx)
          frame-offset       (mf/use-ctx frame-offset-ctx)
          show-interactions  (mf/deref ref:viewer-show-interactions)
          overlays           (mf/deref refs/viewer-overlays)
          interactions       (:interactions shape)
          svg-element?       (and (= :svg-raw (:type shape))
                                  (not= :svg (get-in shape [:content :tag])))

          ;; The objects parameter has the shapes that we must draw. It may be a subset of
          ;; all-objects in some cases (e.g. if there are fixed elements). But for interactions
          ;; handling we need access to all objects inside the page.

          on-pointer-down
          (mf/use-fn (mf/deps shape base-frame frame-offset all-objects)
                     #(on-pointer-down % shape base-frame frame-offset all-objects overlays))

          on-pointer-up
          (mf/use-fn (mf/deps shape base-frame frame-offset all-objects)
                     #(on-pointer-up % shape base-frame frame-offset all-objects overlays))

          on-pointer-enter
          (mf/use-fn (mf/deps shape base-frame frame-offset all-objects)
                     #(on-pointer-enter % shape base-frame frame-offset all-objects overlays))

          on-pointer-leave
          (mf/use-fn (mf/deps shape base-frame frame-offset all-objects)
                     #(on-pointer-leave % shape base-frame frame-offset all-objects overlays))]

      (mf/with-effect []
        (let [sems (on-load shape base-frame frame-offset objects overlays)]
          (partial run! tm/dispose! sems)))

      (if-not svg-element?
        [:> shape-container {:shape shape
                             :cursor (when (ctsi/actionable? interactions) "pointer")
                             :on-pointer-down on-pointer-down
                             :on-pointer-up on-pointer-up
                             :on-pointer-enter on-pointer-enter
                             :on-pointer-leave on-pointer-leave}

         [:& component {:shape shape
                        :frame frame
                        :childs childs
                        :is-child-selected? true
                        :objects objects}]

         [:& interaction {:shape shape
                          :interactions interactions
                          :show-interactions show-interactions}]]

        ;; Don't wrap svg elements inside a <g> otherwise some can break
        [:& component {:shape shape
                       :frame frame
                       :childs childs
                       :objects objects}]))))

(defn frame-wrapper
  [shape-container]
  (generic-wrapper-factory (frame/frame-shape shape-container)))

(defn group-wrapper
  [shape-container]
  (generic-wrapper-factory (group/group-shape shape-container)))

(defn bool-wrapper
  [shape-container]
  (generic-wrapper-factory (bool/bool-shape shape-container)))

(defn svg-raw-wrapper
  [shape-container]
  (generic-wrapper-factory (svg-raw/svg-raw-shape shape-container)))

(defn rect-wrapper
  []
  (generic-wrapper-factory rect/rect-shape))

(defn image-wrapper
  []
  (generic-wrapper-factory image/image-shape))

(defn path-wrapper
  []
  (generic-wrapper-factory path/path-shape))

(defn text-wrapper
  []
  (generic-wrapper-factory text/text-shape))

(defn circle-wrapper
  []
  (generic-wrapper-factory circle/circle-shape))

(declare shape-container-factory)

(defn frame-container-factory
  [objects all-objects]
  (let [shape-container (shape-container-factory objects all-objects)
        frame-wrapper   (frame-wrapper shape-container)
        lookup-xf       (keep (d/getf objects))]
    (mf/fnc frame-container
      {::mf/wrap-props false}
      [props]
      (let [shape  (unchecked-get props "shape")
            childs (into [] lookup-xf (:shapes shape))
            props  (obj/merge props
                              #js {:childs childs
                                   :objects objects
                                   :all-objects all-objects})]
        [:> frame-wrapper props]))))

(defn group-container-factory
  [objects all-objects]
  (let [shape-container (shape-container-factory objects all-objects)
        group-wrapper (group-wrapper shape-container)]
    (mf/fnc group-container
      {::mf/wrap-props false}
      [props]
      (let [childs   (mapv #(get objects %) (:shapes (unchecked-get props "shape")))
            props    (obj/merge! #js {} props
                                 #js {:childs childs
                                      :objects objects
                                      :all-objects all-objects})]
        (when (not-empty childs)
          [:> group-wrapper props])))))

(defn bool-container-factory
  [objects all-objects]
  (let [shape-container (shape-container-factory objects all-objects)
        bool-wrapper (bool-wrapper shape-container)]
    (mf/fnc bool-container
      {::mf/wrap-props false}
      [props]
      (let [childs (->> (cfh/get-children-ids objects (:id (unchecked-get props "shape")))
                        (select-keys objects))
            props  (obj/merge! #js {} props
                               #js {:childs childs
                                    :objects objects})]
        [:> bool-wrapper props]))))

(defn svg-raw-container-factory
  [objects all-objects]
  (let [shape-container (shape-container-factory objects all-objects)
        svg-raw-wrapper (svg-raw-wrapper shape-container)]
    (mf/fnc svg-raw-container
      {::mf/wrap-props false}
      [props]
      (let [childs (mapv #(get objects %) (:shapes (unchecked-get props "shape")))
            props  (obj/merge! #js {} props
                               #js {:childs childs
                                    :objects objects})]
        [:> svg-raw-wrapper props]))))

(defn shape-container-factory
  [objects all-objects]
  (let [path-wrapper   (path-wrapper)
        text-wrapper   (text-wrapper)
        rect-wrapper   (rect-wrapper)
        image-wrapper  (image-wrapper)
        circle-wrapper (circle-wrapper)]
    (mf/fnc shape-container
      {::mf/wrap-props false
       ::mf/wrap [mf/memo]}
      [props]
      (let [shape   (unchecked-get props "shape")
            frame   (unchecked-get props "frame")

            group-container
            (mf/with-memo [objects]
              (group-container-factory objects all-objects))

            frame-container
            (mf/with-memo [objects]
              (frame-container-factory objects all-objects))

            bool-container
            (mf/with-memo [objects]
              (bool-container-factory objects all-objects))

            svg-raw-container
            (mf/with-memo [objects]
              (svg-raw-container-factory objects all-objects))]
        (when (and shape (not (:hidden shape)))
          (let [shape (if frame
                        (gsh/translate-to-frame shape frame)
                        shape)

                opts #js {:shape shape
                          :objects objects
                          :all-objects all-objects}]
            (case (:type shape)
              :frame   [:> frame-container opts]
              :text    [:> text-wrapper opts]
              :rect    [:> rect-wrapper opts]
              :path    [:> path-wrapper opts]
              :image   [:> image-wrapper opts]
              :circle  [:> circle-wrapper opts]
              :group   [:> group-container {:shape shape :frame frame :objects objects}]
              :bool    [:> bool-container {:shape shape :frame frame :objects objects}]
              :svg-raw [:> svg-raw-container {:shape shape :frame frame :objects objects}])))))))

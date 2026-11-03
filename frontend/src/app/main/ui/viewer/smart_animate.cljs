;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.viewer.smart-animate
  "Smart animate for the SVG viewer.

  Both states (two screens or two overlays) are already rendered when the
  transition starts. Layers of the destination with the same name path as
  a layer of the origin are moved and resized from the origin geometry to
  their own; layers that also look different are cross-faded with a copy
  of the origin layer. Layers present in only one state fade in or out.

  Geometry is interpolated with axis-aligned transforms on the rendered
  SVG groups, so the frames are not rendered again on every frame."
  (:require
   [app.common.geom.rect :as grc]
   [app.util.dom :as dom]))

(def ^:private epsilon 0.01)

;; --- Name paths

(defn name-paths
  "Map of name path -> shape id for the subtree of `frame-id`. The frame
  itself has the empty path. Paths shared by several layers are ambiguous
  and therefore left out."
  [objects frame-id]
  (letfn [(walk [acc id path]
            (reduce (fn [acc child-id]
                      (if-let [child (get objects child-id)]
                        (let [child-path (conj path (:name child))]
                          (walk (update acc child-path (fnil conj []) child-id)
                                child-id
                                child-path))
                        acc))
                    acc
                    (get-in objects [id :shapes])))]
    (->> (walk {[] [frame-id]} frame-id [])
         (keep (fn [[path ids]]
                 (when (= 1 (count ids))
                   [path (first ids)])))
         (into {}))))

(def ^:private visual-keys
  [:fills :strokes :shadow :blur :opacity :blend-mode
   :r1 :r2 :r3 :r4 :content :metadata :svg-attrs])

(defn same-look?
  "Check if two layers have the same appearance, ignoring geometry."
  [shape-a shape-b]
  (= (select-keys shape-a visual-keys)
     (select-keys shape-b visual-keys)))

;; --- Axis-aligned maps: p' = s * p + t

(def identity-map {:sx 1 :sy 1 :tx 0 :ty 0})

(defn- safe-ratio
  [a b]
  (if (> (abs b) epsilon) (/ a b) 1))

(defn rect-map
  "Map that takes rect `r1` to rect `r2`. When `scale?` is false only the
  position changes (used for texts, which must not be stretched)."
  ([r1 r2] (rect-map r1 r2 true))
  ([r1 r2 scale?]
   (let [sx (if scale? (safe-ratio (:width r2) (:width r1)) 1)
         sy (if scale? (safe-ratio (:height r2) (:height r1)) 1)]
     {:sx sx
      :sy sy
      :tx (- (:x r2) (* sx (:x r1)))
      :ty (- (:y r2) (* sy (:y r1)))})))

(defn compose
  "Map equivalent to applying `g` first and then `f`."
  [f g]
  {:sx (* (:sx f) (:sx g))
   :sy (* (:sy f) (:sy g))
   :tx (+ (* (:sx f) (:tx g)) (:tx f))
   :ty (+ (* (:sy f) (:ty g)) (:ty f))})

(defn invert
  [{:keys [sx sy tx ty]}]
  {:sx (/ 1 sx)
   :sy (/ 1 sy)
   :tx (- (/ tx sx))
   :ty (- (/ ty sy))})

(defn identity-map?
  [{:keys [sx sy tx ty]}]
  (and (< (abs (- sx 1)) 0.001)
       (< (abs (- sy 1)) 0.001)
       (< (abs tx) 0.1)
       (< (abs ty) 0.1)))

(defn- map->css
  [{:keys [sx sy tx ty]}]
  (str "matrix(" sx ",0,0," sy "," tx "," ty ")"))

;; --- DOM helpers

(defn- shape-node
  "Rendered group of a shape inside `container`. Parents of layers fixed
  when scrolling are rendered twice; the last copy is the regular one."
  [container id]
  (let [nodes (dom/query-all container (str "[id=\"shape-" id "\"]"))
        n     (some-> nodes .-length)]
    (when (and n (pos? n))
      (aget nodes (dec n)))))

(defn- root-svg
  [^js node]
  (loop [svg (.-ownerSVGElement node)]
    (if-let [parent (some-> svg .-ownerSVGElement)]
      (recur parent)
      svg)))

(defn- user-space
  "Placement of a rendered frame: the screen origin and scale of its svg
  user space, and the offset between page coordinates and user space."
  [^js root-node frame]
  (let [svg   (root-svg root-node)
        rect  (dom/get-bounding-rect svg)
        width (some-> svg .-viewBox .-baseVal .-width)
        bg    (dom/query root-node ".frame-background")
        bbox  (some-> ^js bg .getBBox)]
    {:x (:left rect)
     :y (:top rect)
     :scale (if (and width (pos? width)) (/ (:width rect) width) 1)
     :dx (if bbox (- (.-x bbox) (-> frame :selrect :x)) 0)
     :dy (if bbox (- (.-y bbox) (-> frame :selrect :y)) 0)}))

(defn- shape-rect
  "Rect of a shape in the user space of `target`, rendered in `source`."
  [shape source target]
  (let [{:keys [x y width height]} (or (some-> (:points shape) grc/points->rect)
                                       (:selrect shape))
        ;; user space of the source svg
        ux (+ x (:dx source))
        uy (+ y (:dy source))
        ;; through the screen into the target user space
        k  (/ (:scale source) (:scale target))]
    {:x (+ (* ux k) (/ (- (:x source) (:x target)) (:scale target)))
     :y (+ (* uy k) (/ (- (:y source) (:y target)) (:scale target)))
     :width (* width k)
     :height (* height k)}))

(defn- children-node
  "Node where the children of a rendered shape live."
  [^js node]
  (or (dom/query node ".frame-children") node))

(defn- animate-node!
  [^js node keyframes timing]
  (dom/set-css-property! node "transform-origin" "0 0")
  (dom/set-css-property! node "transform-box" "view-box")
  (.animate node (clj->js keyframes) timing))

(defn- release-node!
  [^js node]
  (dom/unset-css-property! node "transform-origin")
  (dom/unset-css-property! node "transform-box"))

(defn- background-clone
  "Copy of a rendered layer to cross-fade. Boards keep only their own
  background, as their children are animated on their own."
  [^js node shape]
  (let [clone (.cloneNode node true)]
    (when (= :frame (:type shape))
      (when-let [children (dom/query clone ".frame-children")]
        (.remove ^js children)))
    (.removeAttribute clone "id")
    clone))

;; --- Smart animate

(defn animate!
  "Interpolate the layers of `to-frame-id`, rendered inside `to-node`,
  from the layers of `from-frame-id` rendered inside `from-node`.
  `objects` must contain both subtrees. With `animate-root?` the frames
  themselves are resized too (overlay states); otherwise only their
  content moves (screens). The origin node is hidden while the transition
  runs; the caller removes it afterwards in `on-complete`. Returns false,
  without calling `on-complete`, when the frames are not rendered as SVG."
  [{:keys [from-node to-node from-frame-id to-frame-id objects animation
           animate-root? on-complete]}]
  (let [to-root   (shape-node to-node to-frame-id)
        from-root (shape-node from-node from-frame-id)]

    (if (or (nil? to-root) (nil? from-root)
            (nil? (get objects to-frame-id)) (nil? (get objects from-frame-id)))
      false

      (let [from-paths (name-paths objects from-frame-id)
            to-paths   (name-paths objects to-frame-id)
            to->from   (into {}
                             (keep (fn [[path to-id]]
                                     (when-let [from-id (get from-paths path)]
                                       [to-id from-id])))
                             to-paths)
            from->to   (into {} (map (fn [[k v]] [v k])) to->from)

            to-space   (user-space to-root (get objects to-frame-id))
            from-space (user-space from-root (get objects from-frame-id))
            timing     #js {:duration (:duration animation)
                            :easing (name (:easing animation :ease-out))}

            ;; Rects in the destination user space
            to-rect    (fn [id] (shape-rect (get objects id) to-space to-space))
            from-rect  (fn [id] (shape-rect (get objects id) from-space to-space))
            ;; Rect of an origin layer in its own user space
            from-own   (fn [id] (shape-rect (get objects id) from-space from-space))

            ;; Map taking a matched destination layer to where its origin
            ;; layer was
            own-map
            (fn [to-id]
              (let [from-id (get to->from to-id)]
                (if (or (nil? from-id)
                        (and (= to-id to-frame-id) (not animate-root?)))
                  identity-map
                  (rect-map (to-rect to-id)
                            (from-rect from-id)
                            (not= :text (get-in objects [to-id :type]))))))

            ;; Map applied to a destination layer by itself and its
            ;; ancestors at the start of the transition
            effective-map
            (fn effective-map [to-id]
              (cond
                (contains? to->from to-id) (own-map to-id)
                (= to-id to-frame-id)      identity-map
                :else (effective-map (get-in objects [to-id :parent-id]))))

            parent-map
            (fn [to-id]
              (if (= to-id to-frame-id)
                identity-map
                (effective-map (get-in objects [to-id :parent-id]))))

            top-level?
            (fn [matched id root-id]
              (let [parent-id (get-in objects [id :parent-id])]
                (or (= parent-id root-id)
                    (contains? matched parent-id))))

            nodes  (volatile! #{})
            clones (volatile! [])
            anims  (volatile! [])

            animate!
            (fn [node keyframes]
              (vswap! nodes conj node)
              (vswap! anims conj (animate-node! node keyframes timing)))

            ;; Copy of an origin layer placed in the destination: it starts
            ;; where the origin layer was and ends following `end-map`
            fade-out-clone!
            (fn [from-id parent-node before-node ancestors-map end-map]
              (let [from-node-el (shape-node from-node from-id)]
                (when from-node-el
                  (let [clone (background-clone from-node-el (get objects from-id))
                        place (rect-map (from-own from-id) (from-rect from-id))
                        start (compose (invert ancestors-map) place)
                        end   (compose end-map place)]
                    (if before-node
                      (.insertBefore ^js parent-node clone before-node)
                      (.appendChild ^js parent-node clone))
                    (vswap! clones conj clone)
                    (animate! clone [{:transform (map->css start) :opacity 1}
                                     {:transform (map->css end) :opacity 0}])))))]

        ;; Matched layers move and, when they look different, cross-fade
        (doseq [[to-id from-id] to->from
                :let [to-node-el (shape-node to-node to-id)
                      to-shape   (get objects to-id)]
                :when to-node-el]
          (let [start-map (compose (invert (parent-map to-id)) (own-map to-id))
                end-map   (rect-map (from-rect from-id) (to-rect to-id)
                                    (not= :text (:type to-shape)))]

            (when-not (identity-map? start-map)
              (animate! to-node-el [{:transform (map->css start-map)}
                                    {:transform "none"}]))

            (when-not (same-look? (get objects from-id) to-shape)
              (case (:type to-shape)
                ;; Group look comes from their children
                :group nil

                ;; Boards fade their background below their own children
                :frame
                (let [children (children-node to-node-el)]
                  (fade-out-clone! from-id children (.-firstChild ^js children)
                                   (effective-map to-id) end-map))

                (do
                  (fade-out-clone! from-id (.-parentNode ^js to-node-el) to-node-el
                                   (parent-map to-id) end-map)
                  (animate! to-node-el [{:opacity 0} {:opacity 1}]))))))

        ;; Layers only present in the destination fade in
        (doseq [[_ to-id] to-paths
                :when (and (not (contains? to->from to-id))
                           (top-level? to->from to-id to-frame-id))
                :let [node (shape-node to-node to-id)]
                :when node]
          (animate! node [{:opacity 0} {:opacity 1}]))

        ;; Layers only present in the origin fade out, following their parent
        (doseq [[_ from-id] from-paths
                :when (and (not (contains? from->to from-id))
                           (top-level? from->to from-id from-frame-id))
                :let [from-parent-id (get-in objects [from-id :parent-id])
                      to-parent-id   (if (= from-parent-id from-frame-id)
                                       to-frame-id
                                       (get from->to from-parent-id))
                      to-parent-el   (some->> to-parent-id (shape-node to-node))]
                :when to-parent-el]
          (fade-out-clone! from-id (children-node to-parent-el) nil
                           (effective-map to-parent-id)
                           (rect-map (from-rect from-parent-id) (to-rect to-parent-id))))

        (dom/set-css-property! from-node "visibility" "hidden")

        (let [done (fn []
                     (run! #(.remove ^js %) @clones)
                     (run! release-node! @nodes)
                     (dom/unset-css-property! from-node "visibility")
                     (when on-complete (on-complete)))]
          (if (empty? @anims)
            (done)
            (-> (js/Promise.all (into-array (map #(.-finished ^js %) @anims)))
                (.then done done))))

        true))))

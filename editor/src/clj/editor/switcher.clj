(ns editor.switcher
  (:require [clojure.edn :as edn] 
            [clojure.set :refer [difference union]]
            [dynamo.background :as background]
            [dynamo.buffers :refer :all]
            [dynamo.camera :refer :all]
            [dynamo.file :as file]
            [dynamo.file.protobuf :as protobuf :refer [pb->str]]
            [dynamo.geom :as geom]
            [dynamo.gl :as gl]
            [dynamo.gl.shader :as shader]
            [dynamo.gl.texture :as texture]
            [dynamo.gl.vertex :as vtx]
            [dynamo.graph :as g]
            [dynamo.grid :as grid]
            [dynamo.image :refer :all]
            [dynamo.node :as n]
            [dynamo.project :as p]
            [dynamo.property :as dp]
            [dynamo.system :as ds]
            [dynamo.texture :as tex]
            [dynamo.types :as t :refer :all]
            [dynamo.ui :refer :all]
            [editor.camera :as c]
            [editor.image-node :as ein]
            [editor.scene-editor :as sceneed]
            [internal.render.pass :as pass]
            [plumbing.core :refer [fnk defnk]]
            [schema.core :as s]
            [schema.macros :as sm]
            [service.log :as log])
  (:import [com.dynamo.graphics.proto Graphics$Cubemap Graphics$TextureImage Graphics$TextureImage$Image Graphics$TextureImage$Type]
           [com.jogamp.opengl.util.awt TextRenderer]
           [dynamo.types Region Animation Camera Image TexturePacking Rect EngineFormatTexture AABB TextureSetAnimationFrame TextureSetAnimation TextureSet]
           [java.awt.image BufferedImage]
           [javax.media.opengl GL GL2 GLContext GLDrawableFactory]
           [javax.media.opengl.glu GLU]
           [javax.vecmath Matrix4d Point3d]))

; Config

(def palette-columns 2)
(def group-spacing 20)
(def cell-size 10)
(def cell-size-half (/ cell-size 2.0))
(def palette-cell-size 80)
(def palette-cell-size-half (/ palette-cell-size 2.0))

(def switcher-atlas-file "switcher/switcher.atlas")
(def candy-colors ["red" "blue" "yellow" "green" "orange" "purple"])

(def palette
  [{:name "Regular"
    :images (map (fn [color] (str color "_candy")) candy-colors)}
   {:name "Striped"
    :images (map (fn [color] (str color "_polka_horisontal")) candy-colors)}
   #_DEMO
   #_{:name "Drop"
     :images ["cherry" "hazelnut"]}])

; Render assets

(vtx/defvertex texture-vtx
  (vec4 position)
  (vec2 texcoord0)
  (vec4 color))

(shader/defshader vertex-shader
  (attribute vec4 position)
  (attribute vec2 texcoord0)
  (attribute vec4 color)
  (varying vec2 var_texcoord0)
  (varying vec4 var_color)
  (defn void main []
    (setq gl_Position (* gl_ModelViewProjectionMatrix position))
    (setq var_texcoord0 texcoord0)
    (setq var_color color)))

(shader/defshader fragment-shader
  (varying vec2 var_texcoord0)
  (uniform sampler2D texture)
  (varying vec4 var_color)
  (defn void main []
    (setq gl_FragColor (* var_color (texture2D texture var_texcoord0.xy)))
    ))

(def shader (shader/make-shader vertex-shader fragment-shader))

; Layout

(defn- layout-row [row x y cell-size]
  (let [column-f (fn [idx image] { :x (+ x (* cell-size idx)) :y y :image image})]
    (map-indexed column-f row)))

(defn- layout-group [group x y cell-size]
  (let [row-f (fn [idx row] (layout-row row x (+ y (* idx cell-size)) cell-size))
        images (:images group)
        rows (partition-all palette-columns images)]
    {:name (:name group)
     :x x :y y
     :height (* cell-size (count rows))
     :cells (flatten (map-indexed row-f rows))}))

(defn- layout-palette
  ([groups]
    (layout-palette groups (+ (* 0.5 group-spacing) palette-cell-size-half) (+ group-spacing palette-cell-size-half)))
  ([groups x y]
    (when (seq groups)
      (let [g' (layout-group (first groups) x y palette-cell-size)]
        (conj (layout-palette (rest groups) x
                              (+ y group-spacing (:height g'))) g')))))

(defn layout-level [level]
  (let [width (:width level)
        height (:height level)
        offset-x (* (- 1 width) cell-size 0.5)
        offset-y (* (- 1 height) cell-size 0.5)]
    (for [i (range width)
          j (range height)
          :let [x (+ offset-x (* i cell-size))
                y (- (+ offset-y (* j cell-size)))
                idx [i j]]]
      {:x x :y y :idx idx :image ((:blocks level) idx)})))

; Rendering

(defn render-text
  [ctx ^GL2 gl ^TextRenderer text-renderer ^String chars ^Float xloc ^Float yloc ^Float zloc ^Float scale]
  (gl/gl-push-matrix gl
    (.glScaled gl 1 -1 1)
    (.setColor text-renderer 1 1 1 1)
    (.begin3DRendering text-renderer)
    (.draw3D text-renderer chars xloc yloc zloc scale)
    (.end3DRendering text-renderer)))

(defn render-palette [ctx ^GL2 gl text-renderer gpu-texture vertex-binding layout]
  (doseq [group layout]
    (render-text ctx gl text-renderer (:name group) (- (:x group) palette-cell-size-half) (+ (* 0.25 group-spacing) (- palette-cell-size-half (:y group))) 0 1))
  (let [cell-count (reduce (fn [v0 v1] (+ v0 (count (:cells v1)))) 0 layout)]
   (gl/with-enabled gl [gpu-texture shader vertex-binding]
    (shader/set-uniform shader gl "texture" 0)
    (gl/gl-draw-arrays gl GL/GL_TRIANGLES 0 (* 6 cell-count)))))

(defn render-level [^GL2 gl level gpu-texture vertex-binding layout]
  (let [cell-count (count layout)]
    (gl/with-enabled gl [gpu-texture shader vertex-binding]
      (shader/set-uniform shader gl "texture" 0)
      (gl/gl-draw-arrays gl GL/GL_TRIANGLES 0 (* 6 cell-count)))))

; Vertex generation

(defn anim-uvs [textureset id]
  (let [anim (get (:animations textureset) id)
        frame (first (:frames anim))]
    (if-let [{start :tex-coords-start count :tex-coords-count} frame]
      (mapv #(nth (:tex-coords textureset) %) (range start (+ start count)))
      [[0 0] [0 0]])))

(defn flip [v0 v1]
  [v1 v0])

(defn gen-vertex [x y u v color]
  (doall (concat [x y 0 1 u v] color)))

(defn gen-quad [textureset cell size-half flip-vert]
  (let [x (:x cell)
        y (:y cell)
        x0 (- x size-half)
        y0 (- y size-half)
        x1 (+ x size-half)
        y1 (+ y size-half)
        [[u0 v0] [u1 v1]] (anim-uvs textureset (:image cell))
        [v0 v1] (if flip-vert (flip v0 v1) [v0 v1])
        color (if (:color cell) (:color cell) [1 1 1 1])]
    [(gen-vertex x0 y0 u0 v0 color)
     (gen-vertex x1 y0 u1 v0 color)
     (gen-vertex x0 y1 u0 v1 color)
     (gen-vertex x1 y0 u1 v0 color)
     (gen-vertex x1 y1 u1 v1 color)
     (gen-vertex x0 y1 u0 v1 color)]))

(defn cells->quads [textureset cells size-half flip-vert]
  (mapcat (fn [cell] (gen-quad textureset cell size-half flip-vert)) cells))

(defn gen-palette-vertex-buffer
  [textureset layout size-half active-brush]
  (let [cell-count (reduce (fn [v0 v1] (+ v0 (count (:cells v1)))) 0 layout)
        vbuf  (->texture-vtx (* 6 cell-count))]
    (doseq [group layout]
      (doseq [vertex (cells->quads textureset (map (fn [cell] (if (= active-brush (:image cell)) cell (assoc cell :color [0.9 0.9 0.9 0.6]))) (:cells group)) palette-cell-size-half false)]
        (conj! vbuf vertex)))
    (persistent! vbuf)))

(def active-cell (atom {}))

(defn gen-level-vertex-buffer
  [textureset layout size-half active-brush]
  (let [layout (map (fn [cell] (if (= @active-cell cell) (assoc cell :image active-brush) cell)) layout)
        cell-count (count layout)
        vbuf  (->texture-vtx (* 6 cell-count))]
    (doseq [vertex (cells->quads textureset layout cell-size-half true)]
      (conj! vbuf vertex))
    (persistent! vbuf)))

; Node defs

(defnk produce-renderable
  [this level gpu-texture palette-vertex-binding level-vertex-binding active-brush palette-layout level-layout]
  {pass/overlay [{:world-transform geom/Identity4d 
                  :render-fn (fn [ctx gl glu text-renderer]
                               (render-palette ctx gl text-renderer gpu-texture palette-vertex-binding palette-layout))}]
   pass/transparent [{:world-transform geom/Identity4d 
                   :render-fn (fn [ctx gl glu text-renderer] 
                                (render-level gl level gpu-texture level-vertex-binding level-layout))}]})

(n/defnode SwitcherRender
  (input level s/Any)
  (input gpu-texture s/Any)
  (input textureset s/Any)
  (input active-brush s/Str)

  (output palette-layout         s/Any :cached (fnk [] (layout-palette palette)))
  (output level-layout           s/Any :cached (fnk [level] (layout-level level)))
  (output palette-vertex-binding s/Any :cached (fnk [textureset palette-layout active-brush] (vtx/use-with (gen-palette-vertex-buffer textureset palette-layout palette-cell-size-half active-brush) shader)))
  (output level-vertex-binding   s/Any :cached (fnk [textureset level-layout active-brush] (vtx/use-with (gen-level-vertex-buffer textureset level-layout cell-size-half active-brush) shader)))
  (output renderable             t/RenderData  produce-renderable))

(defn- hit? [cell pos cell-size-half]
  (let [xc (:x cell)
        yc (:y cell)
        d cell-size-half]
    (when (and (<= (- xc d) (:x pos) (+ xc d))
               (<= (- yc d) (:y pos) (+ yc d)))
      cell)))

(defn handle-input [self action]
  (let [camera (g/node-value self :camera)
        viewport (g/node-value self :viewport)]
    (case (:type action)
      :mouse-moved
      (let [pos {:x (:x action) :y (:y action)}
            world-pos-v4 (c/camera-unproject camera viewport (:x action) (:y action) 0)
            world-pos {:x (.x world-pos-v4) :y (.y world-pos-v4)} 
            level (:level self)
            palette-cells (mapcat :cells (layout-palette palette))
            palette-hit (some #(hit? % pos palette-cell-size-half) palette-cells)
            level-cells (layout-level level)
            level-hit (if palette-hit nil (some #(hit? % world-pos cell-size-half) level-cells))]
        (reset! active-cell level-hit)
        (if active-cell nil action))
      :mouse-pressed
      (let [pos {:x (:x action) :y (:y action)}
            world-pos-v4 (c/camera-unproject camera viewport (:x action) (:y action) 0)
            world-pos {:x (.x world-pos-v4) :y (.y world-pos-v4)} 
            level (:level self)
            palette-cells (mapcat :cells (layout-palette palette))
            palette-hit (some #(hit? % pos palette-cell-size-half) palette-cells)
            level-cells (layout-level level)
            level-hit (if palette-hit nil (some #(hit? % world-pos cell-size-half) level-cells))]
        (when palette-hit
          (ds/tx-label "Select Brush")
          (ds/set-property self :active-brush (:image palette-hit)))
        (when level-hit
          (ds/tx-label "Paint Cell")
          (ds/set-property self :level (assoc-in level [:blocks (:idx level-hit)] (:active-brush self))))
        (if (or palette-hit level-hit) nil action))
      action)))

(n/defnode SwitcherNode
  (inherits n/OutlineNode)

  (property level s/Any (visible false))
  (property width s/Int)
  (property height s/Int)
  (property active-brush s/Str (default "red_candy"))  

  (input camera s/Any)
  (input viewport Region)
  
  (output input-handler Runnable (fnk [] handle-input))
  (output aabb AABB (fnk [width height]
     (let [half-width (* 0.5 cell-size width)
           half-height (* 0.5 cell-size height)]
       (t/->AABB (Point3d. (- half-width) (- half-height) 0)
                 (Point3d. half-width half-height 0)))))

  (on :load
      (let [project (:project event)
            level (edn/read-string (slurp (:filename self)))]
        (ds/set-property self :width (:width level))
        (ds/set-property self :height (:height level))
        (ds/set-property self :level level))))

(defn construct-switcher-editor
  [project-node switcher-node]
  (let [editor (n/construct sceneed/SceneEditor)]
    (ds/in (ds/add editor)
      (let [switcher-render (ds/add (n/construct SwitcherRender))
            renderer        (ds/add (n/construct sceneed/SceneRenderer))
            background      (ds/add (n/construct background/Gradient))
            camera          (ds/add (n/construct c/CameraController :camera (c/make-camera :orthographic)))
            atlas-node      (t/lookup project-node switcher-atlas-file)]
        (ds/connect background   :renderable      renderer     :renderables)
        (ds/connect camera       :camera          renderer     :camera)
        (ds/connect camera       :input-handler   editor       :input-handlers)
        (ds/connect editor       :viewport        camera       :viewport)
        (ds/connect editor       :viewport        renderer     :viewport)
        (ds/connect editor       :drawable        renderer     :drawable)
        (ds/connect renderer     :frame           editor       :frame)

        (ds/update-property camera :movements-enabled disj :tumble) ; TODO - pass in to constructor

        (ds/connect camera         :camera      switcher-node     :camera)
        (ds/connect switcher-node  :input-handler editor         :input-handlers)
        (ds/connect switcher-render :renderable renderer         :renderables)
        (ds/connect switcher-node  :level       switcher-render   :level)
        (ds/connect switcher-node  :active-brush switcher-render   :active-brush)
        (ds/connect editor         :viewport     switcher-node     :viewport)
        (ds/connect atlas-node     :gpu-texture switcher-render   :gpu-texture)
        (ds/connect atlas-node     :textureset  switcher-render   :textureset))
      editor)))

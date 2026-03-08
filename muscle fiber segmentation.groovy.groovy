/**
 * Muscle fiber segmentation (QuPath + BIOP Cellpose) - Cellpose v4 safe
 *
 * Fixes:
 *  - Avoid diameter=0 (Cellpose v4 can crash with division by zero)
 *  - Convert selected objects (Set) -> List (avoids UnmodifiableSet.get error)
 *
 * Usage:
 *  - Draw/select one or more parent annotations
 *  - Run script
 *  - Creates: Fiber + Fiber_Interior under each parent
 */

import qupath.ext.biop.cellpose.Cellpose2D
import qupath.lib.gui.scripting.QPEx
import qupath.lib.objects.PathObjects
import qupath.lib.objects.classes.PathClassFactory
import qupath.lib.roi.RoiTools
import qupath.lib.roi.interfaces.ROI

// ----------------------------------------------------
// Parameters (TUNE THESE)
// ----------------------------------------------------
String cellposeModel = "cyto2"

// IMPORTANT: must be > 0 for your Cellpose v4.0.6 install
// Start with ~70–120 for typical muscle fibers (depends on resolution).
double diameterPx = 90

// Channel indices (0-based)
int chan1 = 0
int chan2 = 0

// Tiling
int tileSizePx = 1024
int tileOverlapPx = 80   // avoids tile-edge artifacts; set ~ (diameterPx * 0.75) if needed

// Thresholds
double flowThreshold = 0.3
double cellprobThreshold = -1.0

// Interior shrink (pixels)
int shrinkPx = 4

// Output types
boolean fibersAsDetections = true
boolean interiorAsDetections = true

// Classes
def fiberClass    = PathClassFactory.getPathClass("Fiber")
def interiorClass = PathClassFactory.getPathClass("Fiber_Interior")

// ----------------------------------------------------
// Sanity checks
// ----------------------------------------------------
def imageData = QPEx.getCurrentImageData()
if (imageData == null)
    throw new IllegalStateException("No image open.")

def selected = QPEx.getSelectedObjects()
if (selected == null || selected.isEmpty())
    throw new IllegalStateException("Select one or more parent annotations (regions) before running.")

// Convert Set -> List (fixes UnmodifiableSet.get error)
def parents = new ArrayList(selected)

println "Cellpose2D resolvable: " + qupath.ext.biop.cellpose.Cellpose2D
println "Parents selected: " + parents.size()

if (diameterPx <= 0)
    throw new IllegalArgumentException("diameterPx must be > 0 for Cellpose v4 (your install crashes at 0).")

// ----------------------------------------------------
// Build & run Cellpose
// ----------------------------------------------------
def cp = Cellpose2D.builder(cellposeModel)
        .diameter(diameterPx)
        .channels(chan1, chan2)
        .tileSize(tileSizePx)
        .setOverlap(tileOverlapPx)          // explicit overlap
        .flowThreshold(flowThreshold)
        .cellprobThreshold(cellprobThreshold)
        .build()

println "Running Cellpose model=" + cellposeModel +
        ", diameterPx=" + diameterPx +
        ", channels=(" + chan1 + "," + chan2 + ")" +
        ", tileSize=" + tileSizePx +
        ", overlap=" + tileOverlapPx
println "Thresholds: flow=" + flowThreshold + ", cellprob=" + cellprobThreshold

cp.detectObjects(imageData, parents)

// ----------------------------------------------------
// Post-process: classify + create interior ROIs
// ----------------------------------------------------
for (int p = 0; p < parents.size(); p++) {

    def parent = parents.get(p)
    def children = parent.getChildObjects()

    if (children == null || children.isEmpty()) {
        println "WARNING: No children created under a parent. Check chan1/thresholds/ROI."
        continue
    }

    def childList = new ArrayList(children)

    for (int i = 0; i < childList.size(); i++) {

        def child = childList.get(i)
        def roi = child.getROI()
        if (roi == null) continue

        // Label as Fiber
        child.setPathClass(fiberClass)

        // (Optional) convert fiber detections to annotations
        def fiberObj = child
        if (!fibersAsDetections && child.isDetection()) {
            def a = PathObjects.createAnnotationObject(roi, fiberClass)
            parent.addChildObject(a)
            parent.removeChildObject(child, true)
            fiberObj = a
        }

        // Interior = shrink inward
        ROI interiorRoi = null
        try {
            interiorRoi = RoiTools.buffer(fiberObj.getROI(), -shrinkPx)
        } catch (Exception e) {
            interiorRoi = null
        }

        if (interiorRoi == null || interiorRoi.isEmpty()) continue

        def interiorObj = interiorAsDetections ?
                PathObjects.createDetectionObject(interiorRoi, interiorClass) :
                PathObjects.createAnnotationObject(interiorRoi, interiorClass)

        parent.addChildObject(interiorObj)
    }
}

QPEx.fireHierarchyUpdate()
println "Done."
println "Tuning:"
println " - Missing fibers: lower cellprobThreshold to -2.0; try flowThreshold=0.2; verify chan1."
println " - Merged fibers: raise cellprobThreshold toward 0.0; increase flowThreshold to 0.4–0.6."
println " - Interior includes membrane: increase shrinkPx."
println " - Tile seams: increase tileOverlapPx (e.g., 100–150)."

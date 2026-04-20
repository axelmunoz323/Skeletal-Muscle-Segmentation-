/**
 * Intracellular border of fiber segmentation (QuPath)
 *
 * Usage:
 *  - Draw/select one or more parent annotations
 *  - Run script
 *  - Creates only: Intracellular Border of Fiber annotations under each parent
 */

import qupath.ext.biop.cellpose.Cellpose2D
import qupath.lib.analysis.images.ContourTracing
import qupath.lib.common.ColorTools
import qupath.lib.gui.scripting.QPEx
import qupath.lib.objects.PathObject
import qupath.lib.objects.PathObjects
import qupath.lib.objects.classes.PathClass
import qupath.lib.regions.ImagePlane
import qupath.lib.regions.RegionRequest
import qupath.lib.roi.RoiTools
import qupath.lib.roi.ROIs
import qupath.lib.roi.interfaces.ROI

import java.awt.image.BufferedImage
import java.util.function.BiFunction

// ----------------------------------------------------
// Fiber segmentation parameters
// ----------------------------------------------------
boolean useCellpose = false
String fiberModel = "cyto2"
double fiberDiameterPx = 45
// BIOP Cellpose uses zero-based channel indices here.
// A one-channel green sarcolemma image should use channels=(0,0).
int fiberChan1 = 0
int fiberChan2 = 0
double fiberFlowThreshold = 0.05
double fiberCellprobThreshold = -4.0

// Direct membrane-image segmentation parameters
// Pixels <= darkInteriorMax are treated as black intracellular fiber space.
// Use -1 for automatic scaling; set manually if needed.
int darkInteriorMax = -1
double maxInteriorAreaFraction = 0.55
boolean excludeBoundaryTouchingInteriors = false

// Interior refinement (pixels)
// Negative RoiTools.buffer values erode/shrink the ROI inward.  This trims the
// sarcolemma/fibrotic rim so the annotation stays on the black cytoplasmic side.
int interiorShrinkPx = 5
double boundarySmoothPx = 1.0
double interiorCleanupPx = 1.0
double minInteriorAreaPx = 500.0
double minFinalInteriorAreaPx = 500.0
double holeFillPx = 0.0

// ----------------------------------------------------
// Shared tiling parameters
// ----------------------------------------------------
int tileSizePx = 640
int tileOverlapPx = 180

// Classes
def interiorClass = PathClass.fromString("Intracellular Border of Fiber")
interiorClass.setColor(ColorTools.packRGB(255, 0, 255))

// ----------------------------------------------------
// Helpers
// ----------------------------------------------------
boolean hasClassName(PathObject obj, String className) {
    def pathClass = obj?.getPathClass()
    if (pathClass == null) return false
    return className.equals(pathClass.getName())
}

void removeGeneratedChildren(def parent) {
    def toRemove = new ArrayList()
    def children = parent.getChildObjects()
    if (children == null) return

    for (def child : children) {
        if (hasClassName(child, "Fiber") ||
                hasClassName(child, "Fiber_Interior") ||
                hasClassName(child, "Central_Nucleus") ||
                hasClassName(child, "Intracellular Border of Fiber")) {
            toRemove.add(child)
        }
    }

    if (!toRemove.isEmpty())
        parent.removeChildObjects(toRemove)
}

List<PathObject> getNewChildren(def parent, Collection<PathObject> beforeChildren) {
    def beforeSet = new LinkedHashSet(beforeChildren)
    def added = new ArrayList<PathObject>()
    def children = parent.getChildObjects()
    if (children == null) return added

    for (def child : children) {
        if (!beforeSet.contains(child))
            added.add(child)
    }
    return added
}

ROI smoothRoi(ROI roi, double smoothPx) {
    if (roi == null || roi.isEmpty() || smoothPx <= 0)
        return roi

    try {
        def expanded = RoiTools.buffer(roi, smoothPx)
        if (expanded == null || expanded.isEmpty())
            return roi

        def smoothed = RoiTools.buffer(expanded, -smoothPx)
        return (smoothed == null || smoothed.isEmpty()) ? roi : smoothed
    } catch (Exception e) {
        return roi
    }
}

ROI makeInteriorRoi(ROI roi, int shrinkPx, double cleanupPx) {
    if (roi == null || roi.isEmpty())
        return null

    try {
        def interior = RoiTools.buffer(roi, -shrinkPx)
        if (interior == null || interior.isEmpty())
            return null

        if (cleanupPx > 0) {
            interior = RoiTools.buffer(interior, -cleanupPx)
            if (interior == null || interior.isEmpty())
                return null

            interior = RoiTools.buffer(interior, cleanupPx)
            if (interior == null || interior.isEmpty())
                return null
        }

        return interior
    } catch (Exception e) {
        return null
    }
}

ROI fillSmallInteriorHoles(ROI roi, double fillPx) {
    if (roi == null || roi.isEmpty() || fillPx <= 0)
        return roi

    try {
        def filled = RoiTools.buffer(roi, fillPx)
        if (filled == null || filled.isEmpty())
            return roi

        filled = RoiTools.buffer(filled, -fillPx)
        return (filled == null || filled.isEmpty()) ? roi : filled
    } catch (Exception e) {
        return roi
    }
}

double getRoiAreaPx(ROI roi) {
    return (roi == null || roi.isEmpty()) ? Double.NaN : roi.getArea()
}

void putMeasurement(PathObject obj, String name, double value) {
    if (obj == null || Double.isNaN(value))
        return

    def measurements = obj.getMeasurementList()
    measurements.putMeasurement(name, value)
}

int getPixelValue(BufferedImage image, int x, int y) {
    def raster = image.getRaster()
    int bands = raster.getNumBands()

    if (bands == 1)
        return raster.getSample(x, y, 0)

    int rgb = image.getRGB(x, y)
    int r = (rgb >> 16) & 0xff
    int g = (rgb >> 8) & 0xff
    int b = rgb & 0xff
    return Math.max(g, Math.max(r, b))
}

int resolveDarkInteriorMax(BufferedImage image, PathObject parent, def request, int configuredMax) {
    if (configuredMax >= 0)
        return configuredMax

    def parentRoi = parent.getROI()
    int width = image.getWidth()
    int height = image.getHeight()
    int maxValue = 0
    for (int y = 0; y < height; y += 4) {
        for (int x = 0; x < width; x += 4) {
            if (!parentRoi.contains(request.getX() + x, request.getY() + y))
                continue
            maxValue = Math.max(maxValue, getPixelValue(image, x, y))
        }
    }

    if (maxValue <= 255)
        return 35
    return Math.max(35, (int)Math.round(maxValue * 0.08))
}

List<PathObject> createDarkInteriorObjects(def imageData, PathObject parent,
        PathClass pathClass, int darkInteriorMax, double maxAreaFraction,
        boolean excludeBoundaryTouching, double minAreaPx) {
    def server = imageData.getServer()
    def parentRoi = parent.getROI()
    def request = RegionRequest.createInstance(server.getPath(), 1.0, parentRoi)
    BufferedImage image = server.readBufferedImage(request)
    int width = image.getWidth()
    int height = image.getHeight()
    int resolvedDarkInteriorMax = resolveDarkInteriorMax(image, parent, request, darkInteriorMax)
    println "Resolved dark interior threshold for parent: " + resolvedDarkInteriorMax
    int maxAreaPx = Math.max(1, (int)Math.round(width * height * maxAreaFraction))

    BufferedImage labels = new BufferedImage(width, height, BufferedImage.TYPE_USHORT_GRAY)
    def labelRaster = labels.getRaster()
    boolean[] visited = new boolean[width * height]
    int[] queueX = new int[width * height]
    int[] queueY = new int[width * height]
    int label = 0

    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            int index = y * width + x
            if (visited[index])
                continue

            visited[index] = true
            double globalX = request.getX() + x
            double globalY = request.getY() + y
            if (!parentRoi.contains(globalX, globalY))
                continue
            if (getPixelValue(image, x, y) > resolvedDarkInteriorMax)
                continue

            int head = 0
            int tail = 0
            int area = 0
            boolean touchesBoundary = false
            queueX[tail] = x
            queueY[tail] = y
            tail++

            while (head < tail) {
                int px = queueX[head]
                int py = queueY[head]
                head++
                area++
                if (px == 0 || py == 0 || px == width - 1 || py == height - 1)
                    touchesBoundary = true

                int nx
                int ny
                int nIndex

                nx = px - 1
                ny = py
                if (nx >= 0) {
                    nIndex = ny * width + nx
                    if (!visited[nIndex]) {
                        visited[nIndex] = true
                        if (parentRoi.contains(request.getX() + nx, request.getY() + ny) &&
                                getPixelValue(image, nx, ny) <= resolvedDarkInteriorMax) {
                            queueX[tail] = nx
                            queueY[tail] = ny
                            tail++
                        }
                    }
                }

                nx = px + 1
                ny = py
                if (nx < width) {
                    nIndex = ny * width + nx
                    if (!visited[nIndex]) {
                        visited[nIndex] = true
                        if (parentRoi.contains(request.getX() + nx, request.getY() + ny) &&
                                getPixelValue(image, nx, ny) <= resolvedDarkInteriorMax) {
                            queueX[tail] = nx
                            queueY[tail] = ny
                            tail++
                        }
                    }
                }

                nx = px
                ny = py - 1
                if (ny >= 0) {
                    nIndex = ny * width + nx
                    if (!visited[nIndex]) {
                        visited[nIndex] = true
                        if (parentRoi.contains(request.getX() + nx, request.getY() + ny) &&
                                getPixelValue(image, nx, ny) <= resolvedDarkInteriorMax) {
                            queueX[tail] = nx
                            queueY[tail] = ny
                            tail++
                        }
                    }
                }

                nx = px
                ny = py + 1
                if (ny < height) {
                    nIndex = ny * width + nx
                    if (!visited[nIndex]) {
                        visited[nIndex] = true
                        if (parentRoi.contains(request.getX() + nx, request.getY() + ny) &&
                                getPixelValue(image, nx, ny) <= resolvedDarkInteriorMax) {
                            queueX[tail] = nx
                            queueY[tail] = ny
                            tail++
                        }
                    }
                }
            }

            if (area < minAreaPx)
                continue
            if (area > maxAreaPx)
                continue
            if (excludeBoundaryTouching && touchesBoundary)
                continue

            label++
            for (int i = 0; i < tail; i++)
                labelRaster.setSample(queueX[i], queueY[i], 0, label)
        }
    }

    if (label == 0)
        return new ArrayList<PathObject>()

    def creator = { ROI roi, Number number ->
        PathObjects.createAnnotationObject(roi, pathClass)
    } as BiFunction<ROI, Number, PathObject>

    return ContourTracing.createObjects(
            labels.getRaster(),
            0,
            request,
            1,
            label,
            creator
    )
}

// ----------------------------------------------------
// Sanity checks
// ----------------------------------------------------
def imageData = QPEx.getCurrentImageData()
if (imageData == null)
    throw new IllegalStateException("No image open.")

double wholeImageAreaPx = (double)imageData.getServer().getWidth() *
        (double)imageData.getServer().getHeight()
int imageChannelCount = imageData.getServer().nChannels()
def pixelCalibration = imageData.getServer().getPixelCalibration()
double pixelAreaUm2 = Double.NaN
if (pixelCalibration != null && pixelCalibration.hasPixelSizeMicrons())
    pixelAreaUm2 = pixelCalibration.getPixelWidthMicrons() *
            pixelCalibration.getPixelHeightMicrons()

def selected = QPEx.getSelectedObjects()
def parents = new ArrayList()
if (selected == null || selected.isEmpty()) {
    println "WARNING: No parent annotation selected. Using the whole image as the parent region."
    def wholeImageRoi = ROIs.createRectangleROI(
            0,
            0,
            imageData.getServer().getWidth(),
            imageData.getServer().getHeight(),
            ImagePlane.getDefaultPlane()
    )
    def wholeImageParent = PathObjects.createAnnotationObject(wholeImageRoi)
    wholeImageParent.setName("Whole image parent")
    QPEx.addObject(wholeImageParent)
    parents.add(wholeImageParent)
} else {
    parents.addAll(selected)
}

println "Cellpose2D resolvable: " + qupath.ext.biop.cellpose.Cellpose2D
println "Parent regions to analyze: " + parents.size()
println "Image channels available: " + imageChannelCount

if (fiberDiameterPx <= 0)
    throw new IllegalArgumentException("fiberDiameterPx must be > 0.")
if (imageChannelCount <= 0)
    throw new IllegalStateException("The current image has no readable channels.")
if (fiberChan1 < 0 || fiberChan1 >= imageChannelCount) {
    println "WARNING: fiberChan1=" + fiberChan1 + " is invalid for imageChannelCount=" +
            imageChannelCount + ". Using channel 0."
    fiberChan1 = 0
}
if (fiberChan2 < 0 || fiberChan2 >= imageChannelCount) {
    println "WARNING: fiberChan2=" + fiberChan2 + " is invalid for imageChannelCount=" +
            imageChannelCount + ". Using channel 0."
    fiberChan2 = 0
}
if (interiorShrinkPx <= 0)
    throw new IllegalArgumentException("interiorShrinkPx must be > 0.")
if (minInteriorAreaPx < 0)
    throw new IllegalArgumentException("minInteriorAreaPx must be >= 0.")
if (minFinalInteriorAreaPx < 0)
    throw new IllegalArgumentException("minFinalInteriorAreaPx must be >= 0.")

for (def parent : parents)
    removeGeneratedChildren(parent)

// ----------------------------------------------------
// Fiber detection
// ----------------------------------------------------
def fiberChildrenBefore = new LinkedHashMap()
if (useCellpose) {
    def fiberDetector = Cellpose2D.builder(fiberModel)
            .diameter(fiberDiameterPx)
            .channels(fiberChan1, fiberChan2)
            .tileSize(tileSizePx)
            .setOverlap(tileOverlapPx)
            .flowThreshold(fiberFlowThreshold)
            .cellprobThreshold(fiberCellprobThreshold)
            .build()

    println "Running Cellpose fiber model=" + fiberModel +
            ", diameterPx=" + fiberDiameterPx +
            ", channels=(" + fiberChan1 + "," + fiberChan2 + ")" +
            ", tileSize=" + tileSizePx +
            ", overlap=" + tileOverlapPx
    println "Fiber thresholds: flow=" + fiberFlowThreshold + ", cellprob=" + fiberCellprobThreshold

    for (def parent : parents)
        fiberChildrenBefore.put(parent, new ArrayList(parent.getChildObjects()))

    fiberDetector.detectObjects(imageData, parents)
} else {
    println "Running direct dark-interior segmentation; Cellpose is disabled."
    println "Direct segmentation threshold: darkInteriorMax=" + darkInteriorMax +
            ", maxInteriorAreaFraction=" + maxInteriorAreaFraction +
            ", excludeBoundaryTouchingInteriors=" + excludeBoundaryTouchingInteriors
}

println "Interior shrinkPx=" + interiorShrinkPx +
        ", boundarySmoothPx=" + boundarySmoothPx +
        ", interiorCleanupPx=" + interiorCleanupPx +
        ", minInteriorAreaPx=" + minInteriorAreaPx +
        ", minFinalInteriorAreaPx=" + minFinalInteriorAreaPx +
        ", holeFillPx=" + holeFillPx

int totalFibersDetected = 0
double totalAreaAllParentsPx = 0.0
double totalAreaAllParentsUm2 = 0.0

for (def parent : parents) {
    def detectedFibers = useCellpose ?
            getNewChildren(parent, fiberChildrenBefore.get(parent)) :
            createDarkInteriorObjects(
                    imageData,
                    parent,
                    interiorClass,
                    darkInteriorMax,
                    maxInteriorAreaFraction,
                    excludeBoundaryTouchingInteriors,
                    minInteriorAreaPx
            )
    int interiorCount = 0
    double totalInteriorAreaPx = 0.0
    double parentAreaPx = getRoiAreaPx(parent.getROI())
    double parentAreaUm2 = parentAreaPx * pixelAreaUm2
    double wholeImageAreaUm2 = wholeImageAreaPx * pixelAreaUm2

    if (detectedFibers.isEmpty())
        println "WARNING: No dark intracellular fiber regions created under a parent. Try increasing darkInteriorMax or decreasing minInteriorAreaPx."

    for (def child : detectedFibers) {
        def roi = child.getROI()
        if (roi == null || roi.isEmpty()) {
            if (useCellpose)
                parent.removeChildObject(child)
            continue
        }

        ROI fiberRoi = smoothRoi(roi, boundarySmoothPx)
        if (fiberRoi == null || fiberRoi.isEmpty()) {
            if (useCellpose)
                parent.removeChildObject(child)
            continue
        }

        ROI interiorRoi = makeInteriorRoi(fiberRoi, interiorShrinkPx, interiorCleanupPx)
        interiorRoi = fillSmallInteriorHoles(interiorRoi, holeFillPx)

        if (useCellpose)
            parent.removeChildObject(child)

        if (interiorRoi == null || interiorRoi.isEmpty())
            continue
        if (interiorRoi.getArea() < minFinalInteriorAreaPx)
            continue

        interiorCount++
        double interiorAreaPx = interiorRoi.getArea()
        totalInteriorAreaPx += interiorAreaPx

        def interiorObj = PathObjects.createAnnotationObject(interiorRoi, interiorClass)
        interiorObj.setName("Intracellular Border of Fiber " + interiorCount)
        putMeasurement(interiorObj, "Fiber ID", interiorCount as double)
        putMeasurement(interiorObj, "Intracellular border area px^2", interiorAreaPx)
        putMeasurement(interiorObj, "Intracellular border area um^2", interiorAreaPx * pixelAreaUm2)
        putMeasurement(interiorObj, "Parent area px^2", parentAreaPx)
        putMeasurement(interiorObj, "Parent area um^2", parentAreaUm2)
        putMeasurement(interiorObj, "Whole image area px^2", wholeImageAreaPx)
        putMeasurement(interiorObj, "Whole image area um^2", wholeImageAreaUm2)
        parent.addChildObject(interiorObj)
    }

    putMeasurement(parent, "Fibers detected", interiorCount as double)
    putMeasurement(parent, "Annotation count", interiorCount as double)
    putMeasurement(parent, "Parent area px^2", parentAreaPx)
    putMeasurement(parent, "Parent area um^2", parentAreaUm2)
    putMeasurement(parent, "Whole image area px^2", wholeImageAreaPx)
    putMeasurement(parent, "Whole image area um^2", wholeImageAreaUm2)
    putMeasurement(parent, "Total intracellular border area px^2", totalInteriorAreaPx)
    putMeasurement(parent, "Total intracellular border area um^2", totalInteriorAreaPx * pixelAreaUm2)
    totalFibersDetected += interiorCount
    totalAreaAllParentsPx += totalInteriorAreaPx
    if (!Double.isNaN(pixelAreaUm2))
        totalAreaAllParentsUm2 += totalInteriorAreaPx * pixelAreaUm2

    println "Created Intracellular Border of Fiber annotations under parent: " + interiorCount
    println "Parent area px^2: " + parentAreaPx
    if (!Double.isNaN(parentAreaUm2))
        println "Parent area um^2: " + parentAreaUm2
    println "Whole image area px^2: " + wholeImageAreaPx
    if (!Double.isNaN(wholeImageAreaUm2))
        println "Whole image area um^2: " + wholeImageAreaUm2
    println "Total intracellular border area px^2: " + totalInteriorAreaPx
    if (!Double.isNaN(pixelAreaUm2))
        println "Total intracellular border area um^2: " + (totalInteriorAreaPx * pixelAreaUm2)
}

QPEx.fireHierarchyUpdate()
println "Done."
println "Total fibers detected across selected parents: " + totalFibersDetected
println "Total intracellular border area across selected parents px^2: " + totalAreaAllParentsPx
if (!Double.isNaN(pixelAreaUm2))
    println "Total intracellular border area across selected parents um^2: " + totalAreaAllParentsUm2
println "Output measurements:"
println " - Fibers detected = number of Intracellular Border of Fiber annotations."
println " - Each annotation has: Fiber ID plus intracellular border, parent, and whole-image area measurements."
println " - Each parent has: Fibers detected, Annotation count, Parent area, Whole image area, Total intracellular border area."
println " - Area is always reported in px^2; um^2 is added when QuPath has pixel-size calibration."
println "Tuning:"
println " - Direct mode is active by default: useCellpose=false."
println " - If no fibers are created: set darkInteriorMax manually higher, e.g. 60 for 8-bit or 1500-5000 for 16-bit."
println " - If only one giant fiber is created: lower darkInteriorMax or maxInteriorAreaFraction."
println " - If green sarcolemma is included inside annotations: lower darkInteriorMax or increase interiorShrinkPx."
println " - If annotations still sit too far from the green border: decrease interiorShrinkPx to 5-6."
println " - If annotations touch green sarcolemma/fibrosis: increase interiorShrinkPx to 9-12."
println " - If small speckles/dots become objects: increase minInteriorAreaPx or minFinalInteriorAreaPx."
println " - If real small fibers disappear: decrease minInteriorAreaPx to 100-300."
println " - If fiber edges are jagged: raise boundarySmoothPx to 2-3."
println " - If neighboring fibers merge through membrane gaps: lower maxInteriorAreaFraction or set excludeBoundaryTouchingInteriors=true."
println " - To try Cellpose again later, set useCellpose=true."

# Skeletal Muscle Segmentation

## Overview

This repository contains scripts for automated segmentation of skeletal muscle fibers from cross-sectional microscopy images using QuPath.

Segmentation is based on **intensity contrast**, identifying **dark intracellular fiber regions bounded by fluorescent membrane signals** (e.g., green outlines in immunofluorescence images). The approach is marker-agnostic and does not rely on specific antibodies.

The workflow generates **intracellular fiber regions** suitable for downstream morphometric analysis.

Script: 

---

## Key Features

* Marker-independent segmentation based on membrane contrast
* Detection of intracellular fiber regions
* Robust to variable staining intensity
* Automated filtering of noise and artifacts
* Built-in morphometric measurements

---

## Requirements

### Core

* QuPath ≥ 0.5

### Optional (for Cellpose mode)

* BIOP Cellpose Extension
* Python 3.10
* Cellpose v3

### OS

* macOS / Linux / Windows

---

## Installation (Optional: Cellpose)

Cellpose is optional and disabled by default.

```bash
micromamba create -n cellpose3 python=3.10
micromamba activate cellpose3
pip install cellpose==3.0.7
```

Set the Python path in QuPath:

```
/Users/username/micromamba/envs/cellpose3/bin/python
```

---

## Running the Segmentation

1. Open your microscopy image in QuPath
2. Draw one or more **parent annotations** around muscle regions
3. Open the Script Editor
4. Run `muscle_fiber_segmentation.groovy`
5. The script creates:

   * **Intracellular Border of Fiber** annotations within each parent region

---

## Segmentation Method

The default workflow uses direct image-based segmentation:

* Detects **dark intracellular regions** within each parent annotation
* Uses an adaptive intensity threshold (`darkInteriorMax`)
* Applies morphological operations:

  * Boundary smoothing
  * Interior shrinking to exclude membrane signal
  * Size-based filtering

This enables consistent segmentation across images with varying fluorescence intensity.

---

## Output

### Per-fiber

* Fiber ID
* Intracellular area (px², µm²)

### Per-parent region

* Number of fibers detected
* Total intracellular fiber area
* Parent region area
* Whole image area

---

## Parameter Tuning

Key adjustable parameters in the script:

* `darkInteriorMax` — intensity threshold for intracellular regions
* `interiorShrinkPx` — controls distance from membrane boundary
* `minInteriorAreaPx` — removes small artifacts
* `maxInteriorAreaFraction` — prevents large merged regions

---

## Optional: Cellpose Mode

To enable Cellpose segmentation:

```groovy
useCellpose = true
```

---

## Citation

If using Cellpose:

Stringer, C., Wang, T., Michaelos, M., & Pachitariu, M.
*Cellpose: a generalist algorithm for cellular segmentation.*
Nature Methods (2021)

---

## License

MIT License

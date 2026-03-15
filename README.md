# Skeletal Muscle Segmentation

## Overview
This repository contains scripts for automated segmentation of skeletal muscle fibers from cross-sectional microscopy images.

The workflow was developed for laminin/WGA/ITG7 stained skeletal muscle sections and uses **QuPath** with the **BIOP Cellpose extension**.

These scripts allow automated identification of individual muscle fibers for downstream morphometric analysis.

---

## Requirements

- QuPath ≥ 0.5
- BIOP Cellpose Extension
- Python 3.10
- Cellpose v3
- macOS / Linux / Windows

---

## Installation

Create a Cellpose environment:

```
micromamba create -n cellpose3 python=3.10
micromamba activate cellpose3
pip install cellpose==3.0.7
```

Then configure the Python path in QuPath:

```
/Users/username/micromamba/envs/cellpose3/bin/python
```

Enter this path in the **Cellpose extension settings in QuPath**.

---

## Running the Segmentation

1. Open your microscopy image in **QuPath**
2. Draw a **parent annotation** around the muscle region
3. Open the **Script Editor**
4. Run `muscle_fiber_segmentation.groovy`
5. Segmented fibers will be created as **detections**

These detections can be used for downstream analysis such as:

- Fiber cross-sectional area
- Fiber size distribution
- Morphometric quantification

---

## Citation

If you use this workflow, please cite:

Stringer, C., Wang, T., Michaelos, M., & Pachitariu, M.  
**Cellpose: a generalist algorithm for cellular segmentation.**  
*Nature Methods* (2021)

---

## License

MIT License
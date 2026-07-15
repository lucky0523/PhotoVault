"""ONNX face detection (SCRFD) + recognition (ArcFace) inference helpers.

Implements the model-specific pre/post-processing for the InsightFace
``buffalo_l`` pack (``det_*.onnx`` SCRFD detector + ``w600k_*.onnx`` ArcFace
recogniser), ported from the InsightFace reference implementation but depending
only on ``numpy`` + ``Pillow`` (no OpenCV), so it fits PhotoVault's lightweight,
optional-dependency model.

Public entry points (used by :class:`app.services.face_analyzer.FaceAnalyzer`):

- :func:`detect_faces` — run the detector, return ``(bbox, score, kps)`` per
  face (``bbox`` is ``[x1, y1, x2, y2]``; ``kps`` is 5 ``(x, y)`` landmarks).
- :func:`get_embedding` — align a face by its 5 landmarks and run the
  recogniser, returning a raw feature vector (the caller L2-normalises).

The purely numerical helpers (:func:`nms`, :func:`distance2bbox`,
:func:`umeyama`) are import-safe and unit-testable without any model.
"""

from __future__ import annotations

from typing import Any, List, Optional, Tuple

# ArcFace destination landmarks for a 112x112 aligned crop (the InsightFace
# reference template): left-eye, right-eye, nose, left-mouth, right-mouth.
_ARCFACE_DST = [
    (38.2946, 51.6963),
    (73.5318, 51.5014),
    (56.0252, 71.7366),
    (41.5493, 92.3655),
    (70.7299, 92.2041),
]
_ARCFACE_SIZE = 112


# ---------------------------------------------------------------------------
# Pure numerical helpers (no model / no PIL) — unit-testable
# ---------------------------------------------------------------------------


def distance2bbox(points: Any, distance: Any) -> Any:
    """Decode ``[left, top, right, bottom]`` distances to ``[x1, y1, x2, y2]``."""
    import numpy as np

    x1 = points[:, 0] - distance[:, 0]
    y1 = points[:, 1] - distance[:, 1]
    x2 = points[:, 0] + distance[:, 2]
    y2 = points[:, 1] + distance[:, 3]
    return np.stack([x1, y1, x2, y2], axis=-1)


def distance2kps(points: Any, distance: Any) -> Any:
    """Decode per-keypoint ``(dx, dy)`` distances to absolute landmark points."""
    import numpy as np

    preds = []
    for i in range(0, distance.shape[1], 2):
        px = points[:, 0] + distance[:, i]
        py = points[:, 1] + distance[:, i + 1]
        preds.append(px)
        preds.append(py)
    return np.stack(preds, axis=-1)


def nms(dets: Any, thresh: float) -> List[int]:
    """Greedy non-maximum suppression. ``dets`` is ``[[x1,y1,x2,y2,score], ...]``.

    Returns the indices of the kept boxes, ordered by descending score.
    """
    import numpy as np

    if len(dets) == 0:
        return []

    x1 = dets[:, 0]
    y1 = dets[:, 1]
    x2 = dets[:, 2]
    y2 = dets[:, 3]
    scores = dets[:, 4]

    areas = (x2 - x1 + 1) * (y2 - y1 + 1)
    order = scores.argsort()[::-1]

    keep: List[int] = []
    while order.size > 0:
        i = int(order[0])
        keep.append(i)
        xx1 = np.maximum(x1[i], x1[order[1:]])
        yy1 = np.maximum(y1[i], y1[order[1:]])
        xx2 = np.minimum(x2[i], x2[order[1:]])
        yy2 = np.minimum(y2[i], y2[order[1:]])

        w = np.maximum(0.0, xx2 - xx1 + 1)
        h = np.maximum(0.0, yy2 - yy1 + 1)
        inter = w * h
        ovr = inter / (areas[i] + areas[order[1:]] - inter)

        inds = np.where(ovr <= thresh)[0]
        order = order[inds + 1]

    return keep


def umeyama(src: Any, dst: Any) -> Any:
    """Estimate a 2x3 similarity transform mapping ``src`` points onto ``dst``.

    Least-squares similarity (rotation + uniform scale + translation) following
    Umeyama (1991). ``src`` and ``dst`` are ``(N, 2)`` arrays. Returns a
    ``(2, 3)`` affine matrix ``M`` such that ``M @ [x, y, 1]^T ≈ dst``.
    """
    import numpy as np

    src = np.asarray(src, dtype=np.float64)
    dst = np.asarray(dst, dtype=np.float64)
    num = src.shape[0]
    dim = src.shape[1]

    src_mean = src.mean(axis=0)
    dst_mean = dst.mean(axis=0)
    src_demean = src - src_mean
    dst_demean = dst - dst_mean

    A = dst_demean.T @ src_demean / num
    d = np.ones((dim,), dtype=np.float64)
    if np.linalg.det(A) < 0:
        d[dim - 1] = -1

    T = np.eye(dim + 1, dtype=np.float64)
    U, S, Vt = np.linalg.svd(A)
    rank = np.linalg.matrix_rank(A)
    if rank == 0:
        return None
    if rank == dim - 1:
        if np.linalg.det(U) * np.linalg.det(Vt) > 0:
            T[:dim, :dim] = U @ Vt
        else:
            s = d[dim - 1]
            d[dim - 1] = -1
            T[:dim, :dim] = U @ np.diag(d) @ Vt
            d[dim - 1] = s
    else:
        T[:dim, :dim] = U @ np.diag(d) @ Vt

    var_src = src_demean.var(axis=0).sum()
    scale = 1.0 / var_src * (S @ d) if var_src > 0 else 1.0
    T[:dim, dim] = dst_mean - scale * (T[:dim, :dim] @ src_mean)
    T[:dim, :dim] *= scale
    return T[:dim, :]  # (2, 3)


# ---------------------------------------------------------------------------
# Detection (SCRFD)
# ---------------------------------------------------------------------------

# SCRFD output-layout presets keyed by the number of network outputs.
# (fmc, feat_stride_fpn, num_anchors, use_kps)
_SCRFD_LAYOUTS = {
    6: (3, [8, 16, 32], 2, False),
    9: (3, [8, 16, 32], 2, True),
    10: (5, [8, 16, 32, 64, 128], 1, False),
    15: (5, [8, 16, 32, 64, 128], 1, True),
}


def _preprocess_detection(image_rgb: Any, input_size: Tuple[int, int]) -> Tuple[Any, float]:
    """Resize (preserving aspect ratio) + pad + normalise for the detector.

    Returns ``(blob, det_scale)`` where ``blob`` is ``(1, 3, H, W)`` float32 and
    ``det_scale`` maps detected coordinates back to the original image.
    """
    import numpy as np
    from PIL import Image

    in_w, in_h = input_size
    h, w = image_rgb.shape[:2]
    im_ratio = float(h) / float(w)
    model_ratio = float(in_h) / float(in_w)
    if im_ratio > model_ratio:
        new_h = in_h
        new_w = int(new_h / im_ratio)
    else:
        new_w = in_w
        new_h = int(new_w * im_ratio)
    det_scale = float(new_h) / float(h)

    resized = Image.fromarray(image_rgb).resize((new_w, new_h), Image.BILINEAR)
    resized = np.asarray(resized)

    det_img = np.zeros((in_h, in_w, 3), dtype=np.uint8)
    det_img[:new_h, :new_w, :] = resized

    blob = (det_img.astype(np.float32) - 127.5) / 128.0
    blob = np.transpose(blob, (2, 0, 1))[np.newaxis, ...]
    return np.ascontiguousarray(blob, dtype=np.float32), det_scale


def detect_faces(
    session: Any,
    image_rgb: Any,
    input_size: Tuple[int, int] = (640, 640),
    score_thresh: float = 0.5,
    nms_thresh: float = 0.4,
) -> List[Tuple[List[float], float, Optional[List[Tuple[float, float]]]]]:
    """Run the SCRFD detector; return ``(bbox, score, kps)`` per detected face.

    ``bbox`` is ``[x1, y1, x2, y2]`` in original-image pixels, ``score`` the
    detection confidence, and ``kps`` five ``(x, y)`` landmarks (or ``None`` for
    models without keypoints).
    """
    import numpy as np

    blob, det_scale = _preprocess_detection(image_rgb, input_size)
    input_name = session.get_inputs()[0].name
    net_outs = session.run(None, {input_name: blob})

    layout = _SCRFD_LAYOUTS.get(len(net_outs))
    if layout is None:
        raise ValueError(
            f"Unsupported SCRFD output count: {len(net_outs)}"
        )
    fmc, strides, num_anchors, use_kps = layout
    in_w, in_h = input_size

    scores_list = []
    bboxes_list = []
    kpss_list = []

    for idx, stride in enumerate(strides):
        scores = net_outs[idx]
        bbox_preds = net_outs[idx + fmc] * stride
        kps_preds = net_outs[idx + fmc * 2] * stride if use_kps else None

        # Some exports return (1, N, C); squeeze the batch dim.
        scores = scores.reshape(-1)
        bbox_preds = bbox_preds.reshape(-1, 4)
        if kps_preds is not None:
            kps_preds = kps_preds.reshape(-1, 10)

        height = in_h // stride
        width = in_w // stride
        centers = np.stack(
            np.mgrid[:height, :width][::-1], axis=-1
        ).astype(np.float32)
        centers = (centers * stride).reshape(-1, 2)
        if num_anchors > 1:
            centers = np.stack([centers] * num_anchors, axis=1).reshape(-1, 2)

        pos = np.where(scores >= score_thresh)[0]
        if pos.size == 0:
            continue

        bboxes = distance2bbox(centers, bbox_preds)[pos]
        scores_list.append(scores[pos])
        bboxes_list.append(bboxes)
        if kps_preds is not None:
            kpss = distance2kps(centers, kps_preds)[pos]
            kpss_list.append(kpss.reshape(kpss.shape[0], -1, 2))

    if not bboxes_list:
        return []

    scores_all = np.concatenate(scores_list)
    bboxes_all = np.concatenate(bboxes_list) / det_scale
    dets = np.hstack([bboxes_all, scores_all[:, None]]).astype(np.float32)
    keep = nms(dets, nms_thresh)

    kpss_all = None
    if kpss_list:
        kpss_all = np.concatenate(kpss_list) / det_scale

    results = []
    for i in keep:
        bbox = dets[i, :4].tolist()
        score = float(dets[i, 4])
        kps = kpss_all[i].tolist() if kpss_all is not None else None
        kps_tuples = [(float(x), float(y)) for x, y in kps] if kps else None
        results.append((bbox, score, kps_tuples))
    return results


# ---------------------------------------------------------------------------
# Recognition (ArcFace)
# ---------------------------------------------------------------------------


def _align_face(image_rgb: Any, kps: Any) -> Any:
    """Warp a face to a 112x112 aligned crop using its 5 landmarks.

    Uses a similarity transform (Umeyama) estimated from the landmarks onto the
    ArcFace template, applied via Pillow's affine transform (inverse mapping).
    """
    import numpy as np
    from PIL import Image

    M = umeyama(np.asarray(kps, dtype=np.float64), np.asarray(_ARCFACE_DST))
    if M is None:
        # Degenerate landmarks: fall back to a centre resize of the whole image.
        return np.asarray(
            Image.fromarray(image_rgb).resize(
                (_ARCFACE_SIZE, _ARCFACE_SIZE), Image.BILINEAR
            )
        )

    # PIL's AFFINE expects the inverse map (output -> input) coefficients.
    full = np.vstack([M, [0.0, 0.0, 1.0]])
    inv = np.linalg.inv(full)
    a, b, c = inv[0]
    d, e, f = inv[1]

    aligned = Image.fromarray(image_rgb).transform(
        (_ARCFACE_SIZE, _ARCFACE_SIZE),
        Image.AFFINE,
        (a, b, c, d, e, f),
        resample=Image.BILINEAR,
    )
    return np.asarray(aligned)


def _crop_bbox(image_rgb: Any, bbox: Any) -> Any:
    """Fallback alignment: crop the bbox and resize to 112x112."""
    import numpy as np
    from PIL import Image

    h, w = image_rgb.shape[:2]
    x1, y1, x2, y2 = bbox
    x1 = max(0, int(round(x1)))
    y1 = max(0, int(round(y1)))
    x2 = min(w, int(round(x2)))
    y2 = min(h, int(round(y2)))
    if x2 <= x1 or y2 <= y1:
        crop = image_rgb
    else:
        crop = image_rgb[y1:y2, x1:x2, :]
    return np.asarray(
        Image.fromarray(crop).resize((_ARCFACE_SIZE, _ARCFACE_SIZE), Image.BILINEAR)
    )


def get_embedding(
    session: Any,
    image_rgb: Any,
    kps: Optional[Any] = None,
    bbox: Optional[Any] = None,
) -> List[float]:
    """Align a face and run the recogniser, returning the raw embedding vector.

    Alignment prefers the 5-landmark similarity transform; when ``kps`` is not
    available it falls back to a bbox crop. The returned vector is *not*
    L2-normalised (the caller does that).
    """
    import numpy as np

    if kps is not None:
        aligned = _align_face(image_rgb, kps)
    elif bbox is not None:
        aligned = _crop_bbox(image_rgb, bbox)
    else:
        from PIL import Image

        aligned = np.asarray(
            Image.fromarray(image_rgb).resize(
                (_ARCFACE_SIZE, _ARCFACE_SIZE), Image.BILINEAR
            )
        )

    # ArcFace normalisation: (x - 127.5) / 127.5, RGB, NCHW.
    blob = (aligned.astype(np.float32) - 127.5) / 127.5
    blob = np.transpose(blob, (2, 0, 1))[np.newaxis, ...]
    blob = np.ascontiguousarray(blob, dtype=np.float32)

    input_name = session.get_inputs()[0].name
    out = session.run(None, {input_name: blob})[0]
    return np.asarray(out).reshape(-1).astype(np.float32).tolist()

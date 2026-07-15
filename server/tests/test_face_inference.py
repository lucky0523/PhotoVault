"""Tests for the pure numerical helpers in ``face_inference``.

These validate the model-independent math (bbox/keypoint decoding, NMS, the
Umeyama similarity transform, and landmark alignment geometry) without needing
a real ONNX model. They require ``numpy`` (which ships with ``onnxruntime``),
so the whole module is skipped in a Pillow-only lightweight deployment.
"""

from __future__ import annotations

import pytest

np = pytest.importorskip("numpy")

from app.services import face_inference as fi


def test_distance2bbox_decodes_ltrb():
    points = np.array([[10.0, 10.0]])
    distance = np.array([[1.0, 2.0, 3.0, 4.0]])
    out = fi.distance2bbox(points, distance)
    # x1=px-l, y1=py-t, x2=px+r, y2=py+b
    assert out.tolist() == [[9.0, 8.0, 13.0, 14.0]]


def test_distance2kps_decodes_points():
    points = np.array([[10.0, 20.0]])
    distance = np.array([[1.0, 2.0, 3.0, 4.0]])  # two (dx, dy) pairs
    out = fi.distance2kps(points, distance)
    assert out.tolist() == [[11.0, 22.0, 13.0, 24.0]]


def test_nms_keeps_highest_and_suppresses_overlap():
    dets = np.array(
        [
            [0, 0, 10, 10, 0.9],
            [1, 1, 10, 10, 0.8],  # heavily overlaps the first -> suppressed
            [50, 50, 60, 60, 0.7],  # disjoint -> kept
        ],
        dtype=np.float32,
    )
    keep = fi.nms(dets, 0.4)
    assert keep[0] == 0
    assert 2 in keep
    assert 1 not in keep


def test_nms_empty():
    assert fi.nms(np.zeros((0, 5), dtype=np.float32), 0.4) == []


def test_umeyama_recovers_similarity_transform():
    # dst = 2 * src + [3, 5] is a pure scale+translation similarity.
    src = np.array([[0.0, 0.0], [1.0, 0.0], [0.0, 1.0], [1.0, 1.0], [2.0, 3.0]])
    dst = src * 2.0 + np.array([3.0, 5.0])
    M = fi.umeyama(src, dst)
    assert M is not None
    recovered = (M @ np.hstack([src, np.ones((src.shape[0], 1))]).T).T
    assert np.abs(recovered - dst).max() < 1e-6


def test_align_face_returns_112_crop():
    # A synthetic image + arbitrary landmarks should produce a 112x112x3 crop.
    image = np.zeros((200, 200, 3), dtype=np.uint8)
    kps = [(60.0, 70.0), (120.0, 70.0), (90.0, 110.0), (65.0, 150.0), (115.0, 150.0)]
    aligned = fi._align_face(image, kps)
    assert aligned.shape == (112, 112, 3)


def test_crop_bbox_returns_112_crop():
    image = np.zeros((200, 200, 3), dtype=np.uint8)
    out = fi._crop_bbox(image, [10, 10, 100, 100])
    assert out.shape == (112, 112, 3)

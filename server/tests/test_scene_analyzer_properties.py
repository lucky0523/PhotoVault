"""Property-based tests for scene threshold filtering.

Covers Property 2 from the web-explore-people-places-scenes design document.
Each property test runs at least 100 iterations via Hypothesis.
"""

from __future__ import annotations

from hypothesis import given, settings
from hypothesis import strategies as st

from app.services.scene_analyzer import filter_scenes


# Confidence scores as finite floats (allow the full model output range and beyond).
_score = st.floats(
    min_value=-10.0,
    max_value=10.0,
    allow_nan=False,
    allow_infinity=False,
)

# Labels drawn from a small pool so duplicates are exercised frequently.
_label = st.sampled_from(["beach", "park", "concert", "screenshot", "mountain", "city"])

# A confidence threshold anywhere in a plausible range (including edges).
_min_conf = st.floats(
    min_value=-1.0,
    max_value=1.0,
    allow_nan=False,
    allow_infinity=False,
)


# Feature: web-explore-people-places-scenes, Property 2: 场景阈值过滤正确、单调且有序
@given(
    data=st.data(),
    min_conf=_min_conf,
)
@settings(max_examples=200)
def test_filter_scenes_threshold_monotonic_and_ordered(data, min_conf: float):
    """**Validates: Requirements 3.1, 3.2**

    For any score vector, label set and threshold ``min_conf``, ``filter_scenes``
    output satisfies: each confidence >= min_conf; each label comes from the input
    labels; output sorted by confidence descending; no duplicate labels; and the
    output is empty when every score is below min_conf.
    """
    # Generate parallel scores/labels lists of equal length (duplicates allowed).
    n = data.draw(st.integers(min_value=0, max_value=30))
    scores = data.draw(st.lists(_score, min_size=n, max_size=n))
    labels = data.draw(st.lists(_label, min_size=n, max_size=n))

    result = filter_scenes(scores, labels, min_conf)

    input_labels = set(labels)

    # 1) Every kept confidence meets the threshold.
    for _label_out, conf in result:
        assert conf >= min_conf

    # 2) Every kept label comes from the input label set.
    for label_out, _conf in result:
        assert label_out in input_labels

    # 3) Output sorted by confidence descending.
    confs = [conf for _label_out, conf in result]
    assert confs == sorted(confs, reverse=True)

    # 4) No duplicate labels.
    out_labels = [label_out for label_out, _conf in result]
    assert len(out_labels) == len(set(out_labels))

    # 5) When all scores are below the threshold, output is empty.
    if all(float(s) < min_conf for s in scores):
        assert result == []

    # Extra invariant: for each kept label, the confidence is the max over all
    # occurrences of that label among the (thresholded) inputs.
    for label_out, conf in result:
        occurrences = [
            float(s)
            for s, lbl in zip(scores, labels)
            if lbl == label_out and float(s) >= min_conf
        ]
        assert conf == max(occurrences)

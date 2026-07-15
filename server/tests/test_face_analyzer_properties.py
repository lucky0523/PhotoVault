"""Property-based tests for the face clustering decision (task 6.2).

Covers Property 3 from the design of the ``web-explore-people-places-scenes``
feature. The property test runs at least 100 iterations via Hypothesis.
"""

from __future__ import annotations

from typing import List, Optional, Tuple

import pytest
from hypothesis import given, settings
from hypothesis import strategies as st

from app.services.face_analyzer import assign_cluster, cosine_similarity


Vector = List[float]
Cluster = Tuple[int, Vector]


# ---------------------------------------------------------------------------
# Strategies
# ---------------------------------------------------------------------------

# Fixed, small embedding dimensionality per example (4-8 dims). Keeping the
# range small keeps generation cheap while still exercising multi-dim vectors.
_DIM = st.integers(min_value=4, max_value=8)

# Finite float components in a reasonable range. Bounds are wide enough to
# exercise sign/magnitude variety but small enough to avoid overflow when the
# module squares/sums them for the cosine norm.
_component = st.floats(
    min_value=-1e3,
    max_value=1e3,
    allow_nan=False,
    allow_infinity=False,
)

# Threshold spans the full cosine-similarity range [-1, 1].
_threshold = st.floats(
    min_value=-1.0,
    max_value=1.0,
    allow_nan=False,
    allow_infinity=False,
)


@st.composite
def _embedding_and_clusters(draw):
    """Draw a (embedding, clusters) pair sharing a common dimensionality.

    Returns an embedding vector plus 0-8 clusters, each a ``(cluster_id,
    centroid)`` tuple whose centroid has the same dimension as the embedding.
    Cluster ids are distinct so equivalence assertions are unambiguous, but the
    decision logic does not depend on that.
    """
    dim = draw(_DIM)
    vec_strategy = st.lists(_component, min_size=dim, max_size=dim)

    embedding = draw(vec_strategy)

    n_clusters = draw(st.integers(min_value=0, max_value=8))
    # Distinct ids drawn from a comfortably large pool.
    ids = draw(
        st.lists(
            st.integers(min_value=1, max_value=10_000),
            min_size=n_clusters,
            max_size=n_clusters,
            unique=True,
        )
    )
    clusters: List[Cluster] = [(cid, draw(vec_strategy)) for cid in ids]
    return embedding, clusters


def _reference_decision(
    embedding: Vector, clusters: List[Cluster], threshold: float
) -> Optional[int]:
    """Independently recompute the expected assignment decision.

    Uses ``max`` + first-``index`` argmax (a different formulation from the
    accumulator loop in the implementation) to derive the highest-similarity
    cluster, then applies the ``>= threshold`` rule. The shared
    ``cosine_similarity`` primitive is intentionally reused so the reference
    inherits the module's documented zero-norm = 0.0 convention and avoids
    float-mismatch flakiness at the decision boundary.
    """
    if not clusters:
        return None
    sims = [cosine_similarity(embedding, centroid) for _, centroid in clusters]
    max_sim = max(sims)
    if max_sim >= threshold:
        # First cluster achieving the maximum similarity wins (matches the
        # implementation's strict-greater tie-breaking).
        return clusters[sims.index(max_sim)][0]
    return None


# ---------------------------------------------------------------------------
# Property 3: 人脸聚类归并决策一致
# ---------------------------------------------------------------------------

# Feature: web-explore-people-places-scenes, Property 3: 人脸聚类归并决策一致
@given(data=_embedding_and_clusters(), threshold=_threshold)
@settings(max_examples=200)
def test_assign_cluster_decision_consistency(data, threshold: float):
    """Feature: web-explore-people-places-scenes, Property 3: 人脸聚类归并决策一致

    **Validates: Requirements 4.2**

    For any embedding, existing cluster centroid set, and similarity threshold
    ``t``, ``assign_cluster`` returns the ``cluster_id`` of the highest-similarity
    cluster IF AND ONLY IF there exists a cluster with similarity ``>= t``;
    otherwise it returns ``None``. Any returned ``cluster_id`` must belong to the
    input cluster set.
    """
    embedding, clusters = data

    result = assign_cluster(embedding, clusters, threshold)
    expected = _reference_decision(embedding, clusters, threshold)
    cluster_ids = {cid for cid, _ in clusters}

    # 1. Decision matches the independently recomputed expected decision.
    assert result == expected

    # 2. Membership: any non-None result is one of the input clusters.
    if result is not None:
        assert result in cluster_ids

    # 3. The iff: None exactly when no cluster meets the threshold (including
    #    the empty-cluster case).
    sims = [cosine_similarity(embedding, centroid) for _, centroid in clusters]
    has_qualifying = bool(sims) and max(sims) >= threshold
    assert (result is not None) == has_qualifying

    # 4. When a result is returned, its own similarity is >= threshold and is
    #    the maximum over all clusters.
    if result is not None:
        chosen_sim = next(
            cosine_similarity(embedding, centroid)
            for cid, centroid in clusters
            if cid == result
        )
        assert chosen_sim >= threshold
        assert chosen_sim == max(sims)


# ---------------------------------------------------------------------------
# Example-based unit tests (complement the property above)
# ---------------------------------------------------------------------------

def test_assign_cluster_empty_returns_none():
    assert assign_cluster([1.0, 0.0, 0.0], [], threshold=0.0) is None


def test_assign_cluster_picks_highest_above_threshold():
    embedding = [1.0, 0.0]
    clusters = [(1, [1.0, 0.0]), (2, [0.0, 1.0])]
    # Cluster 1 is identical (sim 1.0), cluster 2 is orthogonal (sim 0.0).
    assert assign_cluster(embedding, clusters, threshold=0.5) == 1


def test_assign_cluster_none_when_below_threshold():
    embedding = [1.0, 0.0]
    clusters = [(1, [0.0, 1.0])]  # orthogonal -> sim 0.0
    assert assign_cluster(embedding, clusters, threshold=0.5) is None


def test_assign_cluster_tie_prefers_first():
    embedding = [1.0, 1.0]
    clusters = [(7, [1.0, 1.0]), (9, [2.0, 2.0])]  # both sim ~1.0 (colinear)
    # Use a threshold just below 1.0: exact 1.0 is unreachable here because the
    # cosine norm involves sqrt rounding (yields 0.9999999999999998).
    assert assign_cluster(embedding, clusters, threshold=0.99) == 7


def test_assign_cluster_zero_vector_similarity_is_zero():
    # Zero embedding -> cosine 0.0 with everything; joins only if threshold <= 0.
    assert assign_cluster([0.0, 0.0], [(3, [1.0, 0.0])], threshold=0.0) == 3
    assert assign_cluster([0.0, 0.0], [(3, [1.0, 0.0])], threshold=0.1) is None

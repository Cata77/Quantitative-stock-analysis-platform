from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass

import numpy as np
from hmmlearn.hmm import GaussianHMM


@dataclass(frozen=True)
class RegimeReport:
    states: np.ndarray
    mean_returns: np.ndarray
    volatilities: np.ndarray
    transition_matrix: np.ndarray
    converged: bool


def fit_market_regimes(
    returns: Sequence[float],
    *,
    regime_count: int = 2,
    random_state: int = 42,
) -> RegimeReport:
    """Fit Gaussian regimes and relabel them from lowest to highest mean return."""
    if regime_count < 2:
        raise ValueError("regime_count must be at least 2")
    observations = np.asarray(returns, dtype=float)
    observations = observations[np.isfinite(observations)]
    if len(observations) < regime_count * 20:
        raise ValueError("at least 20 observations per regime are required")

    location = float(np.mean(observations))
    scale = float(np.std(observations, ddof=0))
    if scale == 0:
        raise ValueError("returns must have non-zero variance")
    model = GaussianHMM(
        n_components=regime_count,
        covariance_type="full",
        n_iter=500,
        tol=1e-6,
        random_state=random_state,
    )
    samples = ((observations - location) / scale).reshape(-1, 1)
    model.fit(samples)
    original_states = model.predict(samples)
    original_means = location + model.means_.reshape(-1) * scale
    order = np.argsort(original_means)
    new_label = np.empty(regime_count, dtype=int)
    new_label[order] = np.arange(regime_count)
    states = new_label[original_states]
    covariance = np.asarray(model.covars_).reshape(regime_count, -1)[:, 0] * scale**2
    transition = model.transmat_[np.ix_(order, order)]
    return RegimeReport(
        states=states,
        mean_returns=original_means[order],
        volatilities=np.sqrt(covariance[order]),
        transition_matrix=transition,
        converged=bool(model.monitor_.converged),
    )

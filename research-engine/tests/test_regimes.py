import numpy as np

from quant_research.regimes import fit_market_regimes


def test_hmm_regimes_are_relabelled_by_mean_return() -> None:
    random = np.random.default_rng(42)
    returns = np.concatenate(
        [
            random.normal(-0.01, 0.002, 200),
            random.normal(0.01, 0.002, 200),
        ]
    )

    report = fit_market_regimes(returns)

    assert report.converged
    assert report.mean_returns[0] < report.mean_returns[1]
    assert set(report.states) == {0, 1}
    assert report.transition_matrix.shape == (2, 2)

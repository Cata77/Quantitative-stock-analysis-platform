import pytest

from quant_research.config import ResearchSettings


def test_research_settings_enforce_the_plan_cost_range() -> None:
    settings = ResearchSettings("postgresql://reader@example/research")
    assert settings.fees_bps + settings.slippage_bps == 7.5

    with pytest.raises(ValueError):
        ResearchSettings(
            "postgresql://reader@example/research",
            fees_bps=0,
            slippage_bps=0,
        )

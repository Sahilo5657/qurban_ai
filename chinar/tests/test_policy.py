"""Tests for security policy engine."""

import pytest
import time
from pathlib import Path

from chinar.config import ChinarConfig
from chinar.policy import PolicyEngine, ACTION_REGISTRY
from chinar.schemas import CommandIntent, ActionStatus, RiskLevel


@pytest.fixture
def config():
    cfg = ChinarConfig()
    cfg.security.rate_limit = 5
    cfg.security.require_confirmation = True
    cfg.security.allowed_directories = []
    return cfg


@pytest.fixture
def policy(config):
    return PolicyEngine(config)


class TestAuthorization:
    def test_low_risk_action_authorized(self, policy):
        intent = CommandIntent(intent="get_volume")
        result = policy.authorize(intent)
        assert result is None  # None = authorized

    def test_high_risk_requires_confirmation(self, policy):
        intent = CommandIntent(
            intent="execute_shell",
            parameters={"command": "dir"},
        )
        result = policy.authorize(intent)
        assert result is not None
        assert result.status == ActionStatus.CONFIRMATION_REQUIRED

    def test_confirmed_high_risk_passes(self, policy):
        intent = CommandIntent(
            intent="execute_shell",
            parameters={"command": "dir"},
            requires_confirmation=True,
        )
        result = policy.authorize(intent)
        assert result is None  # Authorized because confirmed

    def test_unknown_action_denied(self, policy):
        intent = CommandIntent(intent="nonexistent_action")
        result = policy.authorize(intent)
        assert result is not None
        assert result.status == ActionStatus.DENIED

    def test_emergency_stop_denies_all(self, config, policy):
        config.general.emergency_stop = True
        intent = CommandIntent(intent="get_volume")
        result = policy.authorize(intent)
        assert result is not None
        assert result.status == ActionStatus.DENIED
        assert "emergency" in result.message.lower()


class TestRateLimiting:
    def test_rate_limit_enforced(self, policy):
        intent = CommandIntent(intent="get_volume")
        # Exhaust rate limit (5 per minute)
        for _ in range(5):
            result = policy.authorize(intent)
            assert result is None

        # Next should be denied
        result = policy.authorize(intent)
        assert result is not None
        assert result.status == ActionStatus.DENIED
        assert "rate limit" in result.message.lower()

    def test_zero_rate_limit_is_unlimited(self, config):
        config.security.rate_limit = 0
        policy = PolicyEngine(config)
        intent = CommandIntent(intent="get_volume")
        for _ in range(100):
            result = policy.authorize(intent)
            assert result is None


class TestPathValidation:
    def test_path_traversal_blocked(self, policy):
        intent = CommandIntent(
            intent="open_file",
            parameters={"path": "../../etc/passwd"},
        )
        result = policy.authorize(intent)
        assert result is not None
        assert result.status == ActionStatus.DENIED

    def test_allowed_directory_check(self, config):
        config.security.allowed_directories = ["C:\\Users\\test"]
        policy = PolicyEngine(config)
        intent = CommandIntent(
            intent="open_file",
            parameters={"path": "C:\\Windows\\system32\\cmd.exe"},
        )
        result = policy.authorize(intent)
        assert result is not None
        assert result.status == ActionStatus.DENIED
        assert "outside" in result.message.lower()

    def test_path_within_allowed_dir(self, config):
        allowed = str(Path.home())
        config.security.allowed_directories = [allowed]
        policy = PolicyEngine(config)
        intent = CommandIntent(
            intent="open_file",
            parameters={"path": str(Path.home() / "test.txt")},
        )
        result = policy.authorize(intent)
        # Should pass path validation (may fail on other checks)
        if result is not None:
            assert result.status != ActionStatus.DENIED or "outside" not in result.message


class TestRiskLevels:
    def test_all_registered_actions_have_risk(self, policy):
        for action_name in ACTION_REGISTRY:
            risk = policy.get_risk_level(action_name)
            assert risk is not None
            assert isinstance(risk, RiskLevel)

    def test_execute_shell_is_high_risk(self, policy):
        assert policy.get_risk_level("execute_shell") == RiskLevel.HIGH

    def test_get_volume_is_low_risk(self, policy):
        assert policy.get_risk_level("get_volume") == RiskLevel.LOW

    def test_unknown_action_has_no_risk(self, policy):
        assert policy.get_risk_level("nonexistent") is None

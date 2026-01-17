import subprocess
import sys

import pytest

from src.factorial import factorial


def test_factorial_zero():
    assert factorial(0) == 1


def test_factorial_positive():
    assert factorial(5) == 120


def test_factorial_negative_input():
    with pytest.raises(ValueError):
        factorial(-1)


def test_factorial_non_integer():
    with pytest.raises(TypeError):
        factorial(3.2)


def test_cli_outputs_expected_result():
    result = subprocess.run(
        [sys.executable, "src/factorial.py", "5"],
        capture_output=True,
        text=True,
        check=False,
    )

    assert result.returncode == 0
    assert result.stdout.strip() == "120"
    assert result.stderr.strip() == ""

"""Base adapter protocol and utilities.

All system adapters implement the SystemAdapter protocol, ensuring
consistent interface, error handling, and result reporting.
"""

from __future__ import annotations

import asyncio
import logging
import subprocess
from typing import Any, Protocol

from chinar.schemas import ActionResult, ActionStatus

logger = logging.getLogger("chinar.adapters.base")


class SystemAdapter(Protocol):
    """Protocol for all system operation adapters."""

    async def execute(
        self,
        action: str,
        parameters: dict[str, Any],
    ) -> ActionResult:
        """Execute a system action.

        Args:
            action: The intent name.
            parameters: Validated action parameters.

        Returns:
            An ActionResult indicating success or failure.
        """
        ...


async def run_subprocess(
    args: list[str],
    timeout: int = 30,
    shell: bool = False,
    capture_output: bool = True,
) -> tuple[int, str, str]:
    """Run a subprocess safely with timeout.

    Args:
        args: Command and arguments as a list.
        timeout: Maximum seconds to wait.
        shell: Whether to use shell execution (avoid with untrusted input).
        capture_output: Whether to capture stdout/stderr.

    Returns:
        Tuple of (return_code, stdout, stderr).
    """
    try:
        if shell:
            cmd = " ".join(args) if isinstance(args, list) else args
        else:
            cmd = args

        process = await asyncio.create_subprocess_exec(
            *cmd,
            stdout=subprocess.PIPE if capture_output else subprocess.DEVNULL,
            stderr=subprocess.PIPE if capture_output else subprocess.DEVNULL,
            creationflags=subprocess.CREATE_NO_WINDOW if hasattr(subprocess, 'CREATE_NO_WINDOW') else 0,
        )

        stdout_bytes, stderr_bytes = await asyncio.wait_for(
            process.communicate(),
            timeout=timeout,
        )

        stdout = stdout_bytes.decode("utf-8", errors="replace") if stdout_bytes else ""
        stderr = stderr_bytes.decode("utf-8", errors="replace") if stderr_bytes else ""

        return process.returncode or 0, stdout.strip(), stderr.strip()

    except asyncio.TimeoutError:
        logger.warning("Subprocess timed out after %ds: %s", timeout, args)
        try:
            process.kill()
        except Exception:
            pass
        return -1, "", "Process timed out"
    except FileNotFoundError:
        return -1, "", f"Command not found: {args[0] if args else 'unknown'}"
    except Exception as exc:
        return -1, "", str(exc)


async def run_powershell(command: str, timeout: int = 30) -> tuple[int, str, str]:
    """Run a PowerShell command safely.

    Args:
        command: The PowerShell command string.
        timeout: Maximum seconds to wait.

    Returns:
        Tuple of (return_code, stdout, stderr).
    """
    args = [
        "powershell.exe", "-NoProfile", "-NonInteractive",
        "-ExecutionPolicy", "Bypass", "-Command", command,
    ]
    return await run_subprocess(args, timeout=timeout)

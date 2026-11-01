"""Match Java DTO bounds without silently modifying experiment text."""

import math
from collections.abc import Mapping
from datetime import datetime, timezone
from numbers import Real
from typing import TypeAlias

from .errors import ValidationError

Scalar: TypeAlias = str | int | float | bool
LONG_MAX = 2**63 - 1
INT_MAX = 2**31 - 1


def text(value: object, field: str, maximum: int, *, blank: bool = False) -> str:
    if not isinstance(value, str):
        raise ValidationError(f"{field} must be a string")
    if not blank and not value.strip():
        raise ValidationError(f"{field} must not be blank")
    try:
        length = len(value.encode("utf-16-le")) // 2
    except UnicodeEncodeError:
        raise ValidationError(f"{field} must contain valid Unicode") from None
    if length > maximum:
        raise ValidationError(f"{field} exceeds {maximum} UTF-16 units")
    return value


def integer(value: object, field: str, maximum: int = LONG_MAX, minimum: int = 0) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or not minimum <= value <= maximum:
        raise ValidationError(f"{field} must be an integer between {minimum} and {maximum}")
    return value


def number(value: object, field: str, *, nonnegative: bool = False) -> float:
    if isinstance(value, bool) or not isinstance(value, Real):
        raise ValidationError(f"{field} must be a real number")
    try:
        result = float(value)
    except (OverflowError, ValueError):
        raise ValidationError(f"{field} must be finite") from None
    if not math.isfinite(result) or (nonnegative and result < 0):
        raise ValidationError(
            f"{field} must be finite" + (" and nonnegative" if nonnegative else "")
        )
    return result


def mapping(value: object, field: str, *, empty: bool = False) -> Mapping:
    if not isinstance(value, Mapping) or len(value) > 100 or (not empty and not value):
        raise ValidationError(
            f"{field} must be a mapping with {'0' if empty else '1'} to 100 items"
        )
    return value


def string_map(value: object, field: str, *, empty: bool = False) -> dict[str, str]:
    result = {}
    for key, item in mapping(value, field, empty=empty).items():
        key = text(key, f"{field} key", 100)
        if isinstance(item, str):
            converted = item
        elif isinstance(item, bool):
            converted = "true" if item else "false"
        elif isinstance(item, int):
            if item.bit_length() > 6644:
                raise ValidationError(f"{field} value exceeds 2000 UTF-16 units")
            converted = str(item)
        elif isinstance(item, float) and math.isfinite(item):
            converted = str(item)
        else:
            raise ValidationError(f"{field} values must be strings, finite numbers, or booleans")
        result[key] = text(converted, f"{field} value", 2000, blank=True)
    return result


def timestamp(value: datetime | None) -> str | None:
    if value is None:
        return None
    if not isinstance(value, datetime) or value.tzinfo is None or value.utcoffset() is None:
        raise ValidationError("recorded_at must be a timezone-aware datetime")
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def truncate(value: str, maximum: int) -> str:
    return value.encode("utf-16-le", errors="replace")[: maximum * 2].decode(
        "utf-16-le", errors="ignore"
    )

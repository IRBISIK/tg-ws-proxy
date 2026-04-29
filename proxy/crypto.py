from __future__ import annotations

try:
    from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes  # type: ignore

    class _Ctx:
        __slots__ = ("_impl",)

        def __init__(self, key: bytes, iv: bytes):
            self._impl = Cipher(algorithms.AES(key), modes.CTR(iv)).encryptor()

        def update(self, data: bytes) -> bytes:
            return self._impl.update(data)

except Exception:  # pragma: no cover
    try:
        from java import jclass  # type: ignore

        _JCipher = jclass("javax.crypto.Cipher")
        _JSecretKeySpec = jclass("javax.crypto.spec.SecretKeySpec")
        _JIvParameterSpec = jclass("javax.crypto.spec.IvParameterSpec")

        class _Ctx:
            __slots__ = ("_impl",)

            def __init__(self, key: bytes, iv: bytes):
                self._impl = _JCipher.getInstance("AES/CTR/NoPadding")
                self._impl.init(
                    _JCipher.ENCRYPT_MODE,
                    _JSecretKeySpec(key, "AES"),
                    _JIvParameterSpec(iv),
                )

            def update(self, data: bytes) -> bytes:
                out = self._impl.update(data)
                return bytes(out) if out is not None else b""

    except Exception:
        class _Ctx:  # pragma: no cover
            def __init__(self, key: bytes, iv: bytes):
                raise RuntimeError(
                    "No AES-CTR backend found. Install cryptography or use Android runtime."
                )

def aes_ctr_ctx(key: bytes, iv: bytes):
    return _Ctx(key, iv)

"""Make the recognition models the app bundles.

board.onnx gets its matrix weights in int8 (dynamic quantization, per
channel): on a benchmark of 300 boards it gave the same reading as the
original on every square, about twice as fast, and 38 MB instead of 125 MB.
locator.onnx (mostly convolutions, little to gain) is copied as it is.

Usage: python3 quantize_models.py SRC_DIR DST_DIR
(SRC_DIR holds chessimg2pos v2's board.onnx and locator.onnx.)
"""
import os
import shutil
import sys

from onnxruntime.quantization import QuantType, quantize_dynamic

src, dst = sys.argv[1], sys.argv[2]
os.makedirs(dst, exist_ok=True)
quantize_dynamic(
    os.path.join(src, "board.onnx"),
    os.path.join(dst, "board.onnx"),
    weight_type=QuantType.QInt8,
    op_types_to_quantize=["MatMul", "Gemm"],
    per_channel=True,
)
shutil.copy(os.path.join(src, "locator.onnx"), os.path.join(dst, "locator.onnx"))
for name in ("board.onnx", "locator.onnx"):
    print(name, os.path.getsize(os.path.join(dst, name)) // 1_000_000, "MB")

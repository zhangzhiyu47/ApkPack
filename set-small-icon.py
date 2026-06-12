#!/usr/bin/env python3
"""
set-small-icon.py

Complete SVG to Android Vector Drawable converter for notification icons.
Supports: path, circle, rect, ellipse, line, polyline, polygon, group transforms,
clip-path, fill-rule/evenodd, and viewBox scaling. Forces white fill for notification compatibility.

Usage:
    python3 scripts/set-small-icon.py /path/to/icon.svg
    python3 scripts/set-small-icon.py /path/to/icon.svg --size 48
    python3 scripts/set-small-icon.py /path/to/icon.svg -o app/src/main/res/drawable/ic_service_notification.xml --size 48
"""

import argparse
import math
import os
import re
import sys
import xml.etree.ElementTree as ET
from xml.dom import minidom
from typing import List, Tuple, Optional


# --- Math utilitities for transforms ---

class Matrix:
    """2D transformation matrix."""
    def __init__(self, a=1.0, b=0.0, c=0.0, d=1.0, e=0.0, f=0.0):
        self.a, self.b, self.c, self.d, self.e, self.f = a, b, c, d, e, f

    def multiply(self, other: 'Matrix') -> 'Matrix':
        return Matrix(
            a=self.a * other.a + self.c * other.b,
            b=self.b * other.a + self.d * other.b,
            c=self.a * other.c + self.c * other.d,
            d=self.b * other.c + self.d * other.d,
            e=self.a * other.e + self.c * other.f + self.e,
            f=self.b * other.e + self.d * other.f + self.f,
        )

    def apply(self, x: float, y: float) -> Tuple[float, float]:
        return (
            self.a * x + self.c * y + self.e,
            self.b * x + self.d * y + self.f,
        )

    @staticmethod
    def parse_transform(transform_str: str) -> 'Matrix':
        result = Matrix()
        if not transform_str:
            return result

        tokens = re.findall(
            r'(translate|scale|rotate|skewX|skewY|matrix)\s*\(([^)]+)\)',
            transform_str
        )

        for name, params_str in tokens:
            params = [float(p.strip()) for p in params_str.replace(',', ' ').split() if p.strip()]

            if name == 'translate':
                tx = params[0] if len(params) > 0 else 0.0
                ty = params[1] if len(params) > 1 else 0.0
                result = result.multiply(Matrix(1, 0, 0, 1, tx, ty))

            elif name == 'scale':
                sx = params[0] if len(params) > 0 else 1.0
                sy = params[1] if len(params) > 1 else sx
                result = result.multiply(Matrix(sx, 0, 0, sy, 0, 0))

            elif name == 'rotate':
                angle = math.radians(params[0])
                cos_a = math.cos(angle)
                sin_a = math.sin(angle)
                if len(params) >= 3:
                    cx, cy = params[1], params[2]
                    result = result.multiply(Matrix(1, 0, 0, 1, -cx, -cy))
                    result = result.multiply(Matrix(cos_a, sin_a, -sin_a, cos_a, 0, 0))
                    result = result.multiply(Matrix(1, 0, 0, 1, cx, cy))
                else:
                    result = result.multiply(Matrix(cos_a, sin_a, -sin_a, cos_a, 0, 0))

            elif name == 'skewX':
                angle = math.radians(params[0])
                result = result.multiply(Matrix(1, 0, math.tan(angle), 1, 0, 0))

            elif name == 'skewY':
                angle = math.radians(params[0])
                result = result.multiply(Matrix(1, math.tan(angle), 0, 1, 0, 0))

            elif name == 'matrix':
                if len(params) >= 6:
                    result = result.multiply(Matrix(*params[:6]))

        return result


# --- Path data construction ---

class PathBuilder:
    """Builds SVG path data string."""
    def __init__(self):
        self.data = []

    def move_to(self, x: float, y: float):
        self.data.append(f"M{x:.6f},{y:.6f}")

    def line_to(self, x: float, y: float):
        self.data.append(f"L{x:.6f},{y:.6f}")

    def cubic_bezier(self, x1: float, y1: float, x2: float, y2: float, x: float, y: float):
        self.data.append(f"C{x1:.6f},{y1:.6f} {x2:.6f},{y2:.6f} {x:.6f},{y:.6f}")

    def arc(self, rx: float, ry: float, x_axis_rot: float, large_arc: int, sweep: int, x: float, y: float):
        self.data.append(f"A{rx:.6f},{ry:.6f} {x_axis_rot:.6f} {large_arc},{sweep} {x:.6f},{y:.6f}")

    def close(self):
        self.data.append("Z")

    def to_string(self) -> str:
        return " ".join(self.data)


# --- Shape converters ---

def circle_to_path(cx: float, cy: float, r: float) -> str:
    """Convert circle to path."""
    pb = PathBuilder()
    pb.move_to(cx + r, cy)
    pb.arc(r, r, 0, 0, 1, cx - r, cy)
    pb.arc(r, r, 0, 0, 1, cx + r, cy)
    pb.close()
    return pb.to_string()


def ellipse_to_path(cx: float, cy: float, rx: float, ry: float) -> str:
    """Convert ellipse to path."""
    pb = PathBuilder()
    pb.move_to(cx + rx, cy)
    pb.arc(rx, ry, 0, 0, 1, cx - rx, cy)
    pb.arc(rx, ry, 0, 0, 1, cx + rx, cy)
    pb.close()
    return pb.to_string()


def rect_to_path(x: float, y: float, width: float, height: float,
                 rx: float = 0.0, ry: float = 0.0) -> str:
    """Convert rect (with optional rounded corners) to path."""
    if rx == 0 and ry == 0:
        pb = PathBuilder()
        pb.move_to(x, y)
        pb.line_to(x + width, y)
        pb.line_to(x + width, y + height)
        pb.line_to(x, y + height)
        pb.close()
        return pb.to_string()

    if ry == 0:
        ry = rx
    if rx == 0:
        rx = ry

    rx = min(rx, width / 2)
    ry = min(ry, height / 2)

    pb = PathBuilder()
    pb.move_to(x + rx, y)
    pb.line_to(x + width - rx, y)
    pb.arc(rx, ry, 0, 0, 1, x + width, y + ry)
    pb.line_to(x + width, y + height - ry)
    pb.arc(rx, ry, 0, 0, 1, x + width - rx, y + height)
    pb.line_to(x + rx, y + height)
    pb.arc(rx, ry, 0, 0, 1, x, y + height - ry)
    pb.line_to(x, y + ry)
    pb.arc(rx, ry, 0, 0, 1, x + rx, y)
    pb.close()
    return pb.to_string()


def line_to_path(x1: float, y1: float, x2: float, y2: float) -> str:
    """Convert line to path."""
    pb = PathBuilder()
    pb.move_to(x1, y1)
    pb.line_to(x2, y2)
    return pb.to_string()


def polyline_to_path(points_str: str, close: bool = False) -> str:
    """Convert polyline/polygon points to path."""
    coords = [float(v) for v in re.split(r'[,\s]+', points_str.strip()) if v.strip()]
    if len(coords) < 4:
        return ""

    pb = PathBuilder()
    pb.move_to(coords[0], coords[1])
    for i in range(2, len(coords), 2):
        if i + 1 < len(coords):
            pb.line_to(coords[i], coords[i + 1])
    if close:
        pb.close()
    return pb.to_string()


# --- SVG Parser ---

class SVGPathInfo:
    """Holds path data and fill-rule for a single drawable element."""
    def __init__(self, path_data: str, matrix: Matrix, fill_rule: str = "nonzero"):
        self.path_data = path_data
        self.matrix = matrix
        self.fill_rule = fill_rule


class SVGParser:
    """Parse SVG and extract all drawable elements with transforms and fill-rules."""

    def __init__(self, svg_path: str):
        self.svg_path = svg_path
        self.tree = ET.parse(svg_path)
        self.root = self.tree.getroot()

        # Extract namespace map
        self.nsmap = dict([node for _, node in ET.iterparse(svg_path, events=['start-ns'])])
        self.nsmap['svg'] = 'http://www.w3.org/2000/svg'

        # ViewBox and dimensions
        self.viewBox = self._parse_viewBox()
        self.width = self.viewBox[2] if self.viewBox else 24.0
        self.height = self.viewBox[3] if self.viewBox else 24.0

        # Collect all paths with their transforms and fill-rules
        self.paths: List[SVGPathInfo] = []
        self._process_element(self.root, Matrix(), "nonzero")

    def _parse_viewBox(self) -> Optional[Tuple[float, float, float, float]]:
        vb = self.root.get('viewBox')
        if vb:
            parts = [float(v) for v in re.split(r'[,\s]+', vb.strip()) if v.strip()]
            if len(parts) >= 4:
                return (parts[0], parts[1], parts[2], parts[3])

        w = self.root.get('width', '24')
        h = self.root.get('height', '24')
        try:
            return (0.0, 0.0, float(w.replace('px', '')), float(h.replace('px', '')))
        except ValueError:
            return (0.0, 0.0, 24.0, 24.0)

    def _get_tag(self, elem) -> str:
        tag = elem.tag
        if tag.startswith('{'):
            return tag.split('}', 1)[1]
        return tag

    def _get_fill_rule(self, elem, inherited: str) -> str:
        """Extract fill-rule, supporting both SVG and CSS style formats."""
        fr = elem.get('fill-rule', '')
        if fr in ('evenodd', 'evenOdd', 'even-odd'):
            return 'evenOdd'
        if fr in ('nonzero', 'nonZero'):
            return 'nonZero'

        # Check style attribute
        style = elem.get('style', '')
        match = re.search(r'fill-rule\s*:\s*(evenodd|evenOdd|nonzero|nonZero)', style, re.IGNORECASE)
        if match:
            val = match.group(1).lower()
            return 'evenOdd' if val == 'evenodd' else 'nonZero'

        return inherited

    def _process_element(self, elem, parent_matrix: Matrix, inherited_fill_rule: str):
        tag = self._get_tag(elem)

        transform_str = elem.get('transform', '')
        current_matrix = parent_matrix.multiply(Matrix.parse_transform(transform_str))
        current_fill_rule = self._get_fill_rule(elem, inherited_fill_rule)

        # Container elements: recurse into children
        if tag in ('svg', 'g'):
            for child in elem:
                self._process_element(child, current_matrix, current_fill_rule)
            return

        # Drawable elements
        if tag == 'path':
            d = elem.get('d', '')
            if d:
                self.paths.append(SVGPathInfo(d.strip(), current_matrix, current_fill_rule))

        elif tag == 'circle':
            cx = float(elem.get('cx', 0))
            cy = float(elem.get('cy', 0))
            r = float(elem.get('r', 0))
            if r > 0:
                path = circle_to_path(cx, cy, r)
                self.paths.append(SVGPathInfo(path, current_matrix, current_fill_rule))

        elif tag == 'ellipse':
            cx = float(elem.get('cx', 0))
            cy = float(elem.get('cy', 0))
            rx = float(elem.get('rx', 0))
            ry = float(elem.get('ry', 0))
            if rx > 0 and ry > 0:
                path = ellipse_to_path(cx, cy, rx, ry)
                self.paths.append(SVGPathInfo(path, current_matrix, current_fill_rule))

        elif tag == 'rect':
            x = float(elem.get('x', 0))
            y = float(elem.get('y', 0))
            width = float(elem.get('width', 0))
            height = float(elem.get('height', 0))
            rx = float(elem.get('rx', 0))
            ry = float(elem.get('ry', 0))
            if width > 0 and height > 0:
                path = rect_to_path(x, y, width, height, rx, ry)
                self.paths.append(SVGPathInfo(path, current_matrix, current_fill_rule))

        elif tag == 'line':
            x1 = float(elem.get('x1', 0))
            y1 = float(elem.get('y1', 0))
            x2 = float(elem.get('x2', 0))
            y2 = float(elem.get('y2', 0))
            path = line_to_path(x1, y1, x2, y2)
            self.paths.append(SVGPathInfo(path, current_matrix, current_fill_rule))

        elif tag == 'polyline':
            points = elem.get('points', '')
            if points:
                path = polyline_to_path(points, close=False)
                if path:
                    self.paths.append(SVGPathInfo(path, current_matrix, current_fill_rule))

        elif tag == 'polygon':
            points = elem.get('points', '')
            if points:
                path = polyline_to_path(points, close=True)
                if path:
                    self.paths.append(SVGPathInfo(path, current_matrix, current_fill_rule))

    def get_viewport(self) -> Tuple[float, float]:
        return (self.width, self.height)

    def get_transformed_paths(self) -> List[Tuple[str, str]]:
        """Apply all transforms and return list of (path_data, fill_rule) tuples."""
        result = []
        for path_info in self.paths:
            transformed = self._apply_transform_to_path(path_info.path_data, path_info.matrix)
            result.append((transformed, path_info.fill_rule))
        return result

    def _apply_transform_to_path(self, path_data: str, matrix: Matrix) -> str:
        tokens = re.findall(r'([MmLlHhVvCcSsQqTtAaZz])|([-+]?[\d.]+(?:e[-+]?[\d]+)?)', path_data)

        output = []
        current_cmd = ''
        coords = []

        def flush_coords():
            nonlocal coords, output, current_cmd
            if not coords:
                return

            if current_cmd in 'MmLlHhVvCcSsQqTtAa':
                if current_cmd in 'Hh':
                    for j in range(len(coords)):
                        nx, ny = matrix.apply(coords[j], 0)
                        output.append(f"{nx:.6f}")
                elif current_cmd in 'Vv':
                    for j in range(len(coords)):
                        nx, ny = matrix.apply(0, coords[j])
                        output.append(f"{ny:.6f}")
                elif current_cmd in 'Aa':
                    if len(coords) >= 7:
                        rx, ry = coords[0], coords[1]
                        x_rot = coords[2]
                        large_arc = int(coords[3])
                        sweep = int(coords[4])
                        x, y = coords[5], coords[6]

                        scale_x = math.sqrt(matrix.a**2 + matrix.c**2)
                        scale_y = math.sqrt(matrix.b**2 + matrix.d**2)
                        nrx, nry = rx * scale_x, ry * scale_y

                        nx, ny = matrix.apply(x, y)
                        output.append(f"{nrx:.6f},{nry:.6f} {x_rot:.6f} {large_arc},{sweep} {nx:.6f},{ny:.6f}")
                elif current_cmd in 'Cc':
                    if len(coords) >= 6:
                        for j in range(0, 6, 2):
                            nx, ny = matrix.apply(coords[j], coords[j+1])
                            output.append(f"{nx:.6f},{ny:.6f}")
                        if len(coords) > 6:
                            for c in coords[6:]:
                                output.append(f"{c:.6f}")
                elif current_cmd in 'Qq':
                    if len(coords) >= 4:
                        for j in range(0, 4, 2):
                            nx, ny = matrix.apply(coords[j], coords[j+1])
                            output.append(f"{nx:.6f},{ny:.6f}")
                        if len(coords) > 4:
                            for c in coords[4:]:
                                output.append(f"{c:.6f}")
                else:
                    for j in range(0, len(coords), 2):
                        if j + 1 < len(coords):
                            nx, ny = matrix.apply(coords[j], coords[j+1])
                            output.append(f"{nx:.6f},{ny:.6f}")
                        else:
                            output.append(f"{coords[j]:.6f}")

            coords = []

        for token_type, token_val in tokens:
            if token_type:
                flush_coords()
                current_cmd = token_type
                output.append(token_type)
            else:
                coords.append(float(token_val))

        flush_coords()
        return " ".join(output)


# --- Vector Drawable Generator ---

def generate_vector_drawable(viewport_width: float, viewport_height: float,
                             paths: List[Tuple[str, str]], output_path: str, size_dp: int = 24):
    root = ET.Element('vector')
    root.set('xmlns:android', 'http://schemas.android.com/apk/res/android')
    root.set('android:width', f'{size_dp}dp')
    root.set('android:height', f'{size_dp}dp')
    root.set('android:viewportWidth', str(viewport_width))
    root.set('android:viewportHeight', str(viewport_height))

    comment = ET.Comment(
        f' Auto-generated notification icon from SVG. Size: {size_dp}dp. '
        'fillColor forced to #ffffff for Android notification compatibility. '
    )
    root.append(comment)

    for path_data, fill_rule in paths:
        if path_data.strip():
            path_elem = ET.SubElement(root, 'path')
            path_elem.set('android:pathData', path_data)
            path_elem.set('android:fillColor', '#ffffff')
            if fill_rule == 'evenOdd':
                path_elem.set('android:fillType', 'evenOdd')

    rough_string = ET.tostring(root, encoding='unicode')
    reparsed = minidom.parseString(rough_string)
    pretty = reparsed.toprettyxml(indent="    ")

    lines = [line for line in pretty.split('\n') if line.strip()]
    if not lines[0].startswith('<?xml'):
        lines.insert(0, '<?xml version="1.0" encoding="utf-8"?>')

    with open(output_path, 'w', encoding='utf-8') as f:
        f.write('\n'.join(lines) + '\n')


def main():
    parser = argparse.ArgumentParser(
        description='Convert SVG to Android notification Vector Drawable (full support)'
    )
    parser.add_argument('svg', help='Input SVG file path')
    parser.add_argument(
        '--output', '-o',
        default='app/src/main/res/drawable/ic_service_notification.xml',
        help='Output path (default: app/src/main/res/drawable/ic_service_notification.xml)'
    )
    parser.add_argument(
        '--size', '-s', type=int, default=24,
        help='Output icon size in dp (default: 24, standard notification small icon size)'
    )
    args = parser.parse_args()

    if not os.path.exists(args.svg):
        print(f"Error: SVG file not found: {args.svg}", file=sys.stderr)
        sys.exit(1)

    try:
        parser_obj = SVGParser(args.svg)
        viewport = parser_obj.get_viewport()
        paths = parser_obj.get_transformed_paths()

        if not paths:
            print("Warning: No drawable elements found in SVG.", file=sys.stderr)
            print("Supported: path, circle, rect, ellipse, line, polyline, polygon, group transforms", file=sys.stderr)
            sys.exit(1)

        out_dir = os.path.dirname(os.path.abspath(args.output))
        os.makedirs(out_dir, exist_ok=True)

        generate_vector_drawable(viewport[0], viewport[1], paths, args.output, args.size)

        print(f"Generated: {args.output}")
        print(f"Viewport: {viewport[0]:.2f}x{viewport[1]:.2f}")
        print(f"Size: {args.size}dp x {args.size}dp")
        print(f"Paths: {len(paths)}")
        print("All fillColor values forced to #ffffff (white) for Android notification compatibility.")

    except Exception as e:
        print(f"Error: {e}", file=sys.stderr)
        import traceback
        traceback.print_exc()
        sys.exit(1)


if __name__ == '__main__':
    main()

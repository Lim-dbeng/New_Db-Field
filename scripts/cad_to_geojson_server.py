# -*- coding: utf-8 -*-
"""
CAD → GeoJSON 변환 프록시 서버 (localhost:5000)
지원: DXF, DWG(ODA 설치 시), DGN(GDAL 설치 시)
LINE, POLYLINE, LWPOLYLINE, ARC, CIRCLE, SPLINE 등 다양한 엔티티 추출 (QGIS와 동일한 형태 목표)
"""
import json
import os
import tempfile
from pathlib import Path

from flask import Flask, request, jsonify, Response

# DXF/DWG 공통: ezdxf
import ezdxf
from shapely.geometry import Polygon, LineString, Point, shape
from pyproj import Transformer
import geopandas as gpd

app = Flask(__name__)
app.config['MAX_CONTENT_LENGTH'] = 100 * 1024 * 1024  # 100MB

# 기본: EPSG:5186 (한국 중부원점) → WGS84. 요청 시 form 'crs' 로 원본 CRS 지정 가능 (예: 5174, 5179, 32652)
DEFAULT_SOURCE_CRS = "5186"

# ARC/CIRCLE/SPLINE 디스크리트화 정밀도 (작을수록 정밀, QGIS와 유사하게)
FLATTEN_SAGITTA = 0.01
FLATTEN_DISTANCE = 0.01


def get_transformer(source_epsg):
    """원본 좌표계(EPSG) → WGS84 변환기."""
    try:
        return Transformer.from_crs("EPSG:" + str(source_epsg).strip(), "EPSG:4326", always_xy=True)
    except Exception:
        return Transformer.from_crs("EPSG:" + DEFAULT_SOURCE_CRS, "EPSG:4326", always_xy=True)


def transform_coords(coords, transformer=None):
    if transformer is None:
        transformer = get_transformer(DEFAULT_SOURCE_CRS)
    return [transformer.transform(x, y) for x, y in coords]


def get_reverse_transformer(target_epsg):
    try:
        return Transformer.from_crs("EPSG:4326", "EPSG:" + str(target_epsg).strip(), always_xy=True)
    except Exception:
        return Transformer.from_crs("EPSG:4326", "EPSG:4326", always_xy=True)


def _to_coords_2d(pts):
    """Vec3/좌표 리스트 → (x,y) 리스트."""
    result = []
    for p in pts:
        if hasattr(p, 'x') and hasattr(p, 'y'):
            result.append((float(p.x), float(p.y)))
        elif hasattr(p, '__getitem__'):
            result.append((float(p[0]), float(p[1])))
        else:
            result.append((float(p[0]), float(p[1])))
    return result


def _add_geom(geometries, coords, transformer, closed=False):
    """좌표 리스트를 변환 후 적절한 geometry로 추가."""
    if not coords:
        return
    transformed = transform_coords(coords, transformer)
    if len(transformed) == 1:
        geometries.append(Point(transformed[0]))
    elif len(transformed) == 2:
        geometries.append(LineString(transformed))
    elif closed and len(transformed) >= 3:
        closed_pts = transformed + [transformed[0]]
        try:
            geom = Polygon(closed_pts)
            geometries.append(geom if geom.is_valid else LineString(transformed))
        except Exception:
            geometries.append(LineString(transformed))
    else:
        geometries.append(LineString(transformed))


def extract_geometries_from_dxf_doc(doc, transformer=None):
    """ezdxf Document에서 LINE, POLYLINE, LWPOLYLINE, ARC, CIRCLE, SPLINE 추출 → Shapely geometry 리스트."""
    msp = doc.modelspace()
    geometries = []

    # 1. LINE
    for e in msp.query('LINE'):
        try:
            start = e.dxf.start
            end = e.dxf.end
            coords = [(start.x, start.y), (end.x, end.y)]
            _add_geom(geometries, coords, transformer, closed=False)
        except Exception:
            pass

    # 2. LWPOLYLINE
    for pline in msp.query('LWPOLYLINE'):
        try:
            coords = [(point[0], point[1]) for point in pline]
            if len(coords) == 0:
                continue
            transformed = transform_coords(coords, transformer)
            if len(transformed) == 1:
                geometries.append(Point(transformed[0]))
            elif len(transformed) == 2:
                geometries.append(LineString(transformed))
            elif pline.closed and len(transformed) >= 3:
                closed_coords = transformed + [transformed[0]]
                try:
                    geom = Polygon(closed_coords)
                    geometries.append(geom if geom.is_valid else LineString(transformed))
                except Exception:
                    geometries.append(LineString(transformed))
            else:
                geometries.append(LineString(transformed))
        except Exception:
            pass

    # 3. POLYLINE (2D/3D)
    for pline in msp.query('POLYLINE'):
        try:
            coords = [(p[0], p[1]) for p in pline.points()]
            if len(coords) < 2:
                continue
            closed = getattr(pline, 'is_closed', False) or pline.is_closed
            _add_geom(geometries, coords, transformer, closed=closed)
        except Exception:
            pass

    # 4. ARC
    for e in msp.query('ARC'):
        try:
            pts = list(e.flattening(FLATTEN_SAGITTA))
            coords = _to_coords_2d(pts)
            if len(coords) >= 2:
                _add_geom(geometries, coords, transformer, closed=False)
        except Exception:
            pass

    # 5. CIRCLE
    for e in msp.query('CIRCLE'):
        try:
            pts = list(e.flattening(FLATTEN_SAGITTA))
            coords = _to_coords_2d(pts)
            if len(coords) >= 3:
                closed_coords = coords + [coords[0]]
                transformed = transform_coords(closed_coords, transformer)
                try:
                    geom = Polygon(transformed)
                    geometries.append(geom if geom.is_valid else LineString(transformed[:-1]))
                except Exception:
                    geometries.append(LineString(transformed[:-1]))
        except Exception:
            pass

    # 6. SPLINE
    for e in msp.query('SPLINE'):
        try:
            pts = list(e.flattening(FLATTEN_DISTANCE, segments=4))
            coords = _to_coords_2d(pts)
            if len(coords) >= 2:
                closed = getattr(e, 'closed', False)
                _add_geom(geometries, coords, transformer, closed=closed)
        except Exception:
            pass

    # 7. ELLIPSE (QGIS에서 지원, distance 파라미터 사용)
    for e in msp.query('ELLIPSE'):
        try:
            pts = list(e.flattening(FLATTEN_DISTANCE, segments=8))
            coords = _to_coords_2d(pts)
            if len(coords) >= 2:
                closed = getattr(e, 'closed', True)
                _add_geom(geometries, coords, transformer, closed=closed)
        except Exception:
            pass

    return geometries


def dxf_to_geojson(dxf_path, source_crs=None):
    """DXF 파일 → GeoJSON 문자열. source_crs: 원본 EPSG 코드(문자열, 예 '5186'). 없으면 기본 5186."""
    doc = ezdxf.readfile(dxf_path)
    transformer = get_transformer(source_crs or DEFAULT_SOURCE_CRS)
    geometries = extract_geometries_from_dxf_doc(doc, transformer)
    if not geometries:
        raise ValueError("DXF 파일에서 추출할 도형을 찾을 수 없습니다.")

    gdf = gpd.GeoDataFrame({'geometry': geometries}, crs='EPSG:4326')
    return gdf.to_json(ensure_ascii=False)


def dwg_to_geojson(dwg_path, source_crs=None):
    """DWG 파일 → GeoJSON 문자열 (ODA File Converter 필요)."""
    try:
        from ezdxf.addons import odafc
    except ImportError:
        raise ValueError("DWG 변환을 위해 ezdxf.addons.odafc가 필요합니다.")

    if not odafc.is_installed():
        raise ValueError(
            "DWG 변환을 위해 ODA File Converter가 설치되어 있어야 합니다. "
            "https://www.opendesign.com/guestfiles/oda_file_converter 에서 설치 후 "
            "Windows: C:\\Program Files\\ODA\\ODAFileConverter\\ODAFileConverter.exe 경로를 확인하세요."
        )

    doc = odafc.readfile(dwg_path)
    transformer = get_transformer(source_crs or DEFAULT_SOURCE_CRS)
    geometries = extract_geometries_from_dxf_doc(doc, transformer)
    if not geometries:
        raise ValueError("DWG 파일에서 추출할 도형을 찾을 수 없습니다.")

    gdf = gpd.GeoDataFrame({'geometry': geometries}, crs='EPSG:4326')
    return gdf.to_json(ensure_ascii=False)


def dgn_to_geojson(dgn_path):
    """DGN 파일 → GeoJSON 문자열 (GDAL/OGR 필요)."""
    try:
        gdf = gpd.read_file(dgn_path)
    except Exception as e:
        raise ValueError(
            "DGN 변환을 위해 GDAL이 설치되어 있어야 합니다. "
            "예: conda install gdal 또는 pip install gdal (플랫폼별 빌드 필요). 오류: " + str(e)
        )

    if gdf is None or len(gdf) == 0:
        raise ValueError("DGN 파일에서 geometry를 찾을 수 없습니다.")

    if gdf.crs:
        gdf = gdf.to_crs(epsg=4326)
    return gdf.to_json(ensure_ascii=False)


def cad_to_geojson(file_path, source_crs=None):
    """확장자에 따라 DXF/DWG/DGN → GeoJSON 디스패치. source_crs: DXF/DWG 원본 EPSG(예 '5179')."""
    ext = Path(file_path).suffix.lower()
    if ext == '.dxf':
        return dxf_to_geojson(file_path, source_crs)
    if ext == '.dwg':
        return dwg_to_geojson(file_path, source_crs)
    if ext == '.dgn':
        return dgn_to_geojson(file_path)
    raise ValueError("지원하지 않는 확장자입니다. .dxf, .dwg, .dgn 만 가능합니다.")


def _transform_coords_for_dxf(coords, transformer):
    return [transformer.transform(float(x), float(y)) for x, y in coords]


def _add_polygon_to_dxf(msp, polygon, transformer):
    exterior = list(polygon.exterior.coords)
    if len(exterior) >= 4:
        msp.add_lwpolyline(_transform_coords_for_dxf(exterior, transformer), close=True)
    for interior in polygon.interiors:
        ring = list(interior.coords)
        if len(ring) >= 4:
            msp.add_lwpolyline(_transform_coords_for_dxf(ring, transformer), close=True)


def _add_geometry_to_dxf(msp, geom, transformer):
    geom_type = geom.geom_type
    if geom_type == "Point":
        x, y = transformer.transform(float(geom.x), float(geom.y))
        msp.add_point((x, y))
    elif geom_type == "LineString":
        coords = list(geom.coords)
        if len(coords) == 1:
            x, y = transformer.transform(float(coords[0][0]), float(coords[0][1]))
            msp.add_point((x, y))
        elif len(coords) >= 2:
            msp.add_lwpolyline(_transform_coords_for_dxf(coords, transformer))
    elif geom_type == "Polygon":
        _add_polygon_to_dxf(msp, geom, transformer)
    elif geom_type.startswith("Multi") or geom_type == "GeometryCollection":
        for part in geom.geoms:
            _add_geometry_to_dxf(msp, part, transformer)


def geojson_to_dxf_bytes(geojson_str, target_crs=None):
    payload = json.loads(geojson_str)
    geometries = []

    if payload.get("type") == "FeatureCollection":
        for feature in payload.get("features", []):
            geometry = feature.get("geometry")
            if geometry:
                geometries.append(shape(geometry))
    elif payload.get("type") == "Feature":
        geometry = payload.get("geometry")
        if geometry:
            geometries.append(shape(geometry))
    elif payload.get("type"):
        geometries.append(shape(payload))

    if not geometries:
        raise ValueError("GeoJSON에서 DXF로 변환할 geometry를 찾을 수 없습니다.")

    transformer = get_reverse_transformer(target_crs or "4326")
    doc = ezdxf.new("R2010")
    msp = doc.modelspace()
    for geom in geometries:
        _add_geometry_to_dxf(msp, geom, transformer)

    fd, tmp = tempfile.mkstemp(suffix=".dxf")
    os.close(fd)
    try:
        doc.saveas(tmp)
        with open(tmp, "rb") as f:
            return f.read()
    finally:
        if os.path.exists(tmp):
            try:
                os.unlink(tmp)
            except Exception:
                pass


@app.route('/dxf-to-geojson', methods=['POST'])
def convert_cad_to_geojson():
    """
    multipart/form-data 로 'file' 키에 DXF/DWG/DGN 파일 업로드.
    응답: GeoJSON 문자열 (Content-Type: application/json).
    """
    if 'file' not in request.files:
        return jsonify({"error": "파일이 없습니다. 'file' 키로 업로드하세요."}), 400

    f = request.files['file']
    if f.filename == '' or not f.filename:
        return jsonify({"error": "파일명이 없습니다."}), 400

    ext = Path(f.filename).suffix.lower()
    if ext not in ('.dxf', '.dwg', '.dgn'):
        return jsonify({"error": "지원 형식: .dxf, .dwg, .dgn"}), 400

    # 원본 좌표계(EPSG). 없으면 기본 5186(한국 중부원점). QGIS에서 DXF CRS 확인 후 맞추면 위치 정확해짐
    source_crs = request.form.get("crs", "").strip() or None
    tmp = None
    try:
        fd, tmp = tempfile.mkstemp(suffix=ext)
        os.close(fd)
        f.save(tmp)
        geojson_str = cad_to_geojson(tmp, source_crs)
        return Response(geojson_str, mimetype='application/json; charset=utf-8')
    except ValueError as e:
        return jsonify({"error": str(e)}), 400
    except Exception as e:
        import traceback
        traceback.print_exc()
        return jsonify({"error": "변환 실패: " + str(e)}), 500
    finally:
        if tmp and os.path.exists(tmp):
            try:
                os.unlink(tmp)
            except Exception:
                pass


@app.route('/geojson-to-dxf', methods=['POST'])
def convert_geojson_to_dxf():
    geojson_str = request.form.get("geojson", "").strip()
    if not geojson_str:
        return jsonify({"error": "geojson 값이 필요합니다."}), 400

    target_crs = request.form.get("target_crs", "").strip() or None
    try:
        dxf_bytes = geojson_to_dxf_bytes(geojson_str, target_crs)
        resp = Response(dxf_bytes, mimetype="application/dxf")
        resp.headers["Content-Disposition"] = "attachment; filename=converted.dxf"
        return resp
    except ValueError as e:
        return jsonify({"error": str(e)}), 400
    except Exception as e:
        import traceback
        traceback.print_exc()
        return jsonify({"error": "DXF 변환 실패: " + str(e)}), 500


@app.route('/health', methods=['GET'])
def health():
    return jsonify({"status": "ok", "service": "cad-to-geojson"})


if __name__ == '__main__':
    port = int(os.environ.get('PORT', 5000))
    print("CAD→GeoJSON 프록시 서버: http://localhost:{}/dxf-to-geojson".format(port))
    app.run(host='127.0.0.1', port=port, debug=False)

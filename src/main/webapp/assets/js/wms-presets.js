"use strict";

// Define code-side presets for GeoServer WMS styles and CQL conditions.
// Fill this mapping with your real rules from SPOTSYSTEM (map.jsp) one-to-one.
// Each key is a full layer name "workspace:layer".
// Each rule may specify: title (for dropdown), style (GeoServer SLD style name),
// cql (CQL_FILTER string), opacity (0..1).
// Example presets are placeholders; replace with your real conditions.

window.NewDbField = window.NewDbField || {};
window.NewDbField.WMS_PRESETS = {
	// Example:
	// "city:parcel": [
	//   { title: "주거지역", style: "parcel_residential", cql: "ZONE = 'R'", opacity: 0.9 },
	//   { title: "상업지역", style: "parcel_commercial",  cql: "ZONE = 'C'", opacity: 0.9 },
	//   { title: "용적률>200", style: "", cql: "FAR > 200", opacity: 1.0 }
	// ],
	// "city:road": [
	//   { title: "주요도로", style: "road_major", cql: "CLASS IN ('EXPR','ART')", opacity: 1.0 },
	//   { title: "보조도로", style: "road_minor", cql: "CLASS IN ('COLL','LOC')", opacity: 1.0 }
	// ]
};

window.NewDbField.defaultWmsLayers = [
	{ name: "fac:gis_a_layer_dbfield", title: "시설물 조사 레이어" },
	{ name: "fac:shp_layer", title: "SHP 레이어" }
];



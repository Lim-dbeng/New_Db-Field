$(".demo").each(function () {
  $(this).minicolors({
    control: $(this).attr("data-control") || "hue",
    defaultValue: $(this).attr("data-defaultValue") || "",
    format: $(this).attr("data-format") || "hex",
    keywords: $(this).attr("data-keywords") || "",
    inline: $(this).attr("data-inline") === "true",
    letterCase: $(this).attr("data-letterCase") || "lowercase",
    opacity: $(this).attr("data-opacity"),
    position: $(this).attr("data-position") || "bottom left",
    swatches: $(this).attr("data-swatches")
      ? $(this).attr("data-swatches").split("|")
      : [],
    change: function (value, opacity) {
      if (!value) return;
      if (typeof console === "object") { 
        var styleMd = new ol.style.Style({
			stroke: new ol.style.Stroke({
				color:'black',
				width: 1
			}),
			fill: new  ol.style.Fill({
		    	color: value,
			})
		})
		mapView.getLayers().getArray()
			  .filter(layer => layer.get('name') === $(this).attr("id"))
			  .forEach(layer => layer.setStyle(styleMd));
      }
    },
    theme: "bootstrap",
  });
});

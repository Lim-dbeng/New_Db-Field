New_Db-Field - Frontend (JSP) scaffold

Setup
- Put your keys into `webapp/WEB-INF/web.xml`:
  - GOOGLE_MAPS_API_KEY
  - VWORLD_API_KEY
  - GEOSERVER_WMS_URL (e.g. http://localhost:8080/geoserver/wms)
- Deploy to Tomcat 9 as a standard WAR (`New_Db-Field` as context).

Notes
- VWorld base uses OpenLayers with WMTS tiles.
- Google base uses Google Maps JS API.
- WMS overlays are requested from GeoServer in EPSG:3857 as 256px tiles.

Default center: Seoul City Hall (37.5665, 126.9780).

Local run (CMD only, no PowerShell)
- Open Command Prompt.
- Run:
  - cd D:\PROJECT\Db-Field\New_Db-Field\scripts
  - nf-start.cmd
- Stop:
  - nf-stop.cmd
- The first run will download Tomcat 9 into `.run/` under the project.



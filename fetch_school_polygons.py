"""
Fetch HCMC school/kindergarten polygons from Overpass API.
Outputs hcmc_school_polygons.json — list of schools, each with either:
  - "polygon": [[lat,lng], ...] (for way/relation features with geometry)
  - "radius_m": 80             (for node-only features, use tight circle fallback)
"""

import json, math, time, urllib.request, urllib.parse

OVERPASS = "https://overpass-api.de/api/interpreter"

QUERY = """
[out:json][timeout:90];
area["name"="Thành phố Hồ Chí Minh"]["admin_level"="4"]->.hcmc;
(
  way["amenity"~"^(school|kindergarten)$"]["name"](area.hcmc);
  relation["amenity"~"^(school|kindergarten)$"]["name"](area.hcmc);
  node["amenity"~"^(school|kindergarten)$"]["name"](area.hcmc);
);
out geom;
"""

def fetch():
    data = urllib.parse.urlencode({"data": QUERY}).encode()
    req  = urllib.request.Request(OVERPASS, data=data,
                                  headers={"User-Agent": "PhotoSync/1.0 school-polygon-fetch"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.loads(r.read().decode())

def centroid(nodes):
    lats = [n[0] for n in nodes]
    lngs = [n[1] for n in nodes]
    return sum(lats)/len(lats), sum(lngs)/len(lngs)

def process(raw):
    results = []
    for el in raw.get("elements", []):
        name = el.get("tags", {}).get("name", "").strip()
        typ  = el.get("tags", {}).get("amenity", "school")
        if not name:
            continue

        if el["type"] == "node":
            results.append({
                "name": name,
                "type": typ,
                "lat":  el["lat"],
                "lng":  el["lon"],
                "radius_m": 80
            })

        elif el["type"] == "way":
            nodes = [[g["lat"], g["lon"]] for g in el.get("geometry", [])]
            if len(nodes) < 3:
                continue
            clat, clng = centroid(nodes)
            results.append({
                "name":    name,
                "type":    typ,
                "lat":     clat,
                "lng":     clng,
                "polygon": nodes
            })

        elif el["type"] == "relation":
            # Use the outer way geometry
            nodes = []
            for member in el.get("members", []):
                if member.get("role") == "outer" and "geometry" in member:
                    nodes = [[g["lat"], g["lon"]] for g in member["geometry"]]
                    break
            if len(nodes) < 3:
                continue
            clat, clng = centroid(nodes)
            results.append({
                "name":    name,
                "type":    typ,
                "lat":     clat,
                "lng":     clng,
                "polygon": nodes
            })

    # Deduplicate by (name, approx lat/lng rounded to 4dp)
    seen = set()
    deduped = []
    for r in results:
        key = (r["name"], round(r["lat"], 4), round(r["lng"], 4))
        if key not in seen:
            seen.add(key)
            deduped.append(r)

    return deduped

print("Fetching from Overpass…")
raw  = fetch()
print(f"Got {len(raw.get('elements', []))} elements")
data = process(raw)
print(f"Processed → {len(data)} unique schools")

poly_count = sum(1 for d in data if "polygon" in d)
node_count = sum(1 for d in data if "radius_m" in d)
print(f"  with polygon: {poly_count}")
print(f"  node-only (80m circle): {node_count}")

out = "C:\\Users\\mcubi\\Desktop\\X\\Phone Tablet Sync\\PhotoSync\\clientapp\\src\\main\\assets\\hcmc_school_polygons.json"
with open(out, "w", encoding="utf-8") as f:
    json.dump(data, f, ensure_ascii=False, separators=(',', ':'))
print(f"Saved → {out}")
print(f"File size: {len(json.dumps(data, ensure_ascii=False)) / 1024:.0f} KB")

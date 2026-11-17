// Local-coordinate triangle meshes shared by the Android and iOS renderers.
#pragma once
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <vector>

namespace cascade {
constexpr float Pi = 3.14159265f;
constexpr float BallRadius = .23f;
constexpr float WallZ = -1.35f;
struct V {
    float x, y, z;
    V operator+(V b) const {
        return {x + b.x, y + b.y, z + b.z};
    }
    V operator-(V b) const {
        return {x - b.x, y - b.y, z - b.z};
    }
    V operator*(float k) const {
        return {x * k, y * k, z * k};
    }
};
inline float dot(V a, V b) {
    return a.x * b.x + a.y * b.y + a.z * b.z;
}
inline V unit(V v) {
    return v * (1.f / std::sqrt(std::max(1.e-12f, dot(v, v))));
}
inline V cross(V a, V b) {
    return {a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x};
}
enum Surface { Face, Skirt, Inlay, Chrome, Iron, Bolt, Accent, Rail, Glass, Copper, TimingArc, Ember,
               Ivory, Ebony, Wood, TubePaint, TubeInside, DrumSkin, DrumShell, LightRim, Tongue, Groove };
struct Vertex {
    V p, n;
    float material, detail;
};
struct Mesh {
    std::vector<Vertex> verts;
    std::vector<uint16_t> indices;
    uint16_t point(V p, V n, Surface s, float detail = 0) {
        uint16_t i = static_cast<uint16_t>(verts.size());
        verts.push_back({p, n, static_cast<float>(s), detail});
        return i;
    }
    void tri(int a, int b, int c) {
        const Vertex& v = verts[a];
        // The presenter uses world Y down, so front faces have the reverse winding.
        if (dot(cross(verts[b].p - v.p, verts[c].p - v.p), v.n + verts[b].n + verts[c].n) > 0)
            std::swap(b, c);
        indices.push_back(a);
        indices.push_back(b);
        indices.push_back(c);
    }
    void quad(int a, int b, int c, int d) {
        tri(a, b, c);
        tri(a, c, d);
    }
};

// Two fine brackets frame the ball at the target. A dark outer edge separates the
// pale inlay from a lit metal face, even when the next cap is nearly vertical.
inline Mesh timingArcs() {
    Mesh m;
    constexpr int segments = 16;
    for (int side = 0; side < 2; ++side) {
        const int base = static_cast<int>(m.verts.size());
        for (int step = 0; step <= segments; ++step) {
            float t = step / static_cast<float>(segments);
            float angle = side * Pi + (t - .5f) * 1.70f;
            V radial{std::cos(angle), 0, std::sin(angle)};
            float taper = .28f + .72f * std::sin(Pi * t);
            m.point(radial, {-.024f * taper, 0, 0}, TimingArc, 0);
            m.point(radial, {-.004f * taper, 0, 0}, TimingArc, 1);
            m.point(radial, {.022f * taper, 0, 0}, TimingArc, .76f);
            if (step > 0) {
                int i = base + (step - 1) * 3;
                m.quad(i, i + 3, i + 4, i + 1);
                m.quad(i + 1, i + 4, i + 5, i + 2);
            }
        }
    }
    return m;
}

// Each triangle fan is a tapered spark, with a luminous centre and a burnt tail.
// Positions carry distance along/across the shard, normals carry launch velocity.
inline Mesh contactSparks() {
    Mesh m;
    const V directions[] = {{-.92f,-.51f,.21f}, {.57f,-.73f,.45f}, {-.28f,-1.03f,-.13f},
                            {.94f,-.35f,-.08f}, {-.66f,-.32f,.63f}, {.20f,-.53f,-.61f}};
    for (V direction : directions) {
        int i = m.point({.016f, 0, 0}, direction, Ember, 1);
        m.point({.082f, 0, 0}, direction, Ember, .67f);
        m.point({0, .027f, 0}, direction, Ember, .55f);
        m.point({-.115f, 0, 0}, direction, Ember, 0);
        m.point({0, -.027f, 0}, direction, Ember, .55f);
        for (int k = 0; k < 4; ++k) m.tri(i, i + 1 + k, i + 1 + (k + 1) % 4);
    }
    return m;
}

// A circular cross section follows the tangent; the cap normals are independent.
inline void tube(Mesh& m, const std::vector<V>& path, float radius, Surface surface, int sides = 8,
                 float detail = 0) {
    if (path.size() < 2)
        return;
    const int begin = static_cast<int>(m.verts.size());
    const V axis = unit(path.back() - path.front());
    const V guide = std::abs(axis.z) < .9f ? V{0, 0, 1} : V{0, 1, 0};
    // The rails and turned hardware use 4–14 sides. Reuse the polar basis for
    // every station and fitting, including when a context is rebuilt after sleep.
    static const auto circles = [] {
        std::array<std::array<V, 16>, 17> result{};
        for (int n = 3; n <= 16; ++n) for (int k = 0; k < n; ++k) {
            const float a = k * 2.f * Pi / n;
            result[n][k] = {std::cos(a), std::sin(a), 0};
        }
        return result;
    }();
    const auto& circle = circles.at(sides);
    for (size_t j = 0; j < path.size(); ++j) {
        V t = unit(path[std::min(j + 1, path.size() - 1)] - path[j ? j - 1 : 0]);
        V b = unit(cross(t, guide)), c = cross(b, t);
        for (int k = 0; k < sides; ++k) {
            V n = b * circle[k].x + c * circle[k].y;
            m.point(path[j] + n * radius, n, surface, detail);
        }
        if (j)
            for (int k = 0; k < sides; ++k) {
                int v = begin + static_cast<int>(j) * sides + k,
                    next = begin + static_cast<int>(j) * sides + (k + 1) % sides;
                m.quad(v - sides, next - sides, next, v);
            }
    }
    for (int cap = 0; cap < 2; ++cap) {
        const size_t end = cap ? path.size() - 1 : 0;
        V n = unit(cap ? path[end] - path[end - 1] : path[0] - path[1]);
        int center = m.point(path[end], n, surface, detail);
        for (int k = 0; k < sides; ++k)
            m.point(m.verts[begin + end * sides + k].p, n, surface, detail);
        for (int k = 0; k < sides; ++k)
            m.tri(center, center + 1 + k, center + 1 + (k + 1) % sides);
    }
}
inline void rod(Mesh& m, V a, V b, float radius, Surface surface, int sides = 8, float detail = 0) {
    tube(m, {a, b}, radius, surface, sides, detail);
}
inline void ellipsoid(Mesh& m, V at, V radii, Surface surface, int sides, int rings) {
    int base = static_cast<int>(m.verts.size());
    for (int j = 0; j <= rings; ++j) {
        float lat = Pi * j / rings, y = -std::cos(lat), ring = std::sin(lat);
        for (int i = 0; i < sides; ++i) {
            float a = i * 2.f * Pi / sides;
            V n{ring * std::cos(a), y, ring * std::sin(a)};
            m.point(at + V{n.x * radii.x, n.y * radii.y, n.z * radii.z},
                    unit({n.x / radii.x, n.y / radii.y, n.z / radii.z}), surface);
        }
    }
    for (int j = 0; j < rings; ++j)
        for (int i = 0; i < sides; ++i) {
            int a = base + j * sides + i, b = base + j * sides + (i + 1) % sides;
            m.quad(a, b, b + sides, a + sides);
        }
}

// The contact plane and outside envelope are common to all records. Change the
// perimeter cuts, fascia and alloy without moving the face under the sphere.
struct KeyDesign {
    int cornerSteps;
    float corner;
    Surface rim, band;
    float lacquer, reflection, grainX, grainZ;
    int instrument; // 0 wood, 1 keys, 2 tubes, 3 pads, 4 metal tongues; fixed for a whole song
    int keyCount = 3;
};
inline const KeyDesign& keyDesign(int motif) {
    static constexpr KeyDesign designs[] = {
        {4, .050f, Ivory, Chrome, .34f, .63f, 1.05f, .14f, 1}, // Floor: amber satin keys
        {3, .058f, Wood, Wood, .70f, .40f, .14f, 2.7f, 0}, // Stereo: suspended marimba bars
        {4, .050f, Ivory, Chrome, .83f, .24f, .14f, 2.7f, 1, 2}, // Gypsy: sea-glass organ levers
        {3, .080f, Chrome, Accent, .82f, .44f, 1.4f, 1.4f, 2}, // Levels: tuned hollow pipes
        {4, .050f, Tongue, Chrome, .65f, .46f, .35f, .35f, 4}, // Stay: brushed metallic tines
        {3, .054f, Chrome, Accent, .86f, .29f, 2.1f, .21f, 3}, // Turn Down: violet electronic pads
        {3, .058f, Wood, Wood, .70f, .40f, .14f, 2.7f, 0}, // Yeah: honey marimba
        {3, .080f, Chrome, Accent, .74f, .62f, 1.8f, .23f, 2}, // Countdown: indigo pipes
    };
    return designs[motif >= 0 && motif < 8 ? motif : 1];
}

inline int keyInstrument(const KeyDesign& design, bool /*accented*/) {
    return design.instrument;
}

inline float keyCenterX(bool shortKey, float side) {
    return side * (shortKey ? .218f : .22f);
}

inline float keyDepth(const KeyDesign& design, bool accented) {
    const int instrument = keyInstrument(design, accented);
    return instrument == 1 ? .082f :
           instrument == 2 ? .206f : instrument == 3 ? .18f :
           instrument == 4 ? .10f : .13f;
}

// A small bevel carries the broad studio light across every key edge. Separate
// top/side vertices prevent a key's planar playing face from shading like a pillow.
inline void sculptedSlab(Mesh& m, float cx, float cz, float width, float length,
                         float y, float depth, float corner, Surface surface) {
    constexpr int steps=5, ring=steps*4;
    struct Slice { float y, inset, slope; };
    const Slice slices[]={{0,.005f,1},{.005f,0,.58f},{depth-.006f,0,0},{depth,.006f,-.64f}};
    auto point=[&](Slice slice,int i) {
        const int q=i/steps;
        const float angle=(q+(i%steps)/float(steps-1))*Pi*.5f;
        const float r=corner-slice.inset;
        return V{cx+(q==0||q==3 ? 1.f:-1.f)*(width*.5f-corner)+r*std::cos(angle),
                 y+slice.y,cz+(q<2 ? 1.f:-1.f)*(length*.5f-corner)+r*std::sin(angle)};
    };
    const int face=m.point({cx,y,cz},{0,-1,0},surface);
    for(int i=0;i<ring;++i) m.point(point(slices[0],i),{0,-1,0},surface);
    for(int i=0;i<ring;++i) m.tri(face,face+1+i,face+1+(i+1)%ring);
    for(int slice=0;slice<3;++slice) {
        const int begin=static_cast<int>(m.verts.size());
        for(int level=0;level<2;++level) for(int i=0;i<ring;++i) {
            const Slice sl=slices[slice+level];
            const float angle=(i/steps+(i%steps)/float(steps-1))*Pi*.5f;
            const float side=std::sqrt(1-sl.slope*sl.slope);
            m.point(point(sl,i),{std::cos(angle)*side,-sl.slope,std::sin(angle)*side},surface);
        }
        for(int i=0;i<ring;++i) m.quad(begin+i,begin+(i+1)%ring,begin+ring+(i+1)%ring,begin+ring+i);
    }
    const int bottom=m.point({cx,y+depth,cz},{0,1,0},surface);
    for(int i=0;i<ring;++i) m.point(point(slices[3],i),{0,1,0},surface);
    for(int i=0;i<ring;++i) m.tri(bottom,bottom+1+i,bottom+1+(i+1)%ring);
}

inline Mesh keyboardMesh(bool shortKey, float side, bool accented, const KeyDesign& design) {
    Mesh m;
    const float width=shortKey ? .46f:.70f, cx=keyCenterX(shortKey,side);
    const float front=.25f, back=shortKey ? -.585f:-.66f;
    const float length=front-back, cz=(front+back)*.5f, depth=keyDepth(design,accented);
    constexpr float gap=.009f;
    const int keyCount=design.keyCount;
    const float spacing=width/keyCount, keyWidth=spacing-gap;
    // Individual floating ivory levers, with real gaps and rounded front lips.
    // The inward key still covers the exact x=0,z=0 impact tangent.
    for(int k=0;k<keyCount;++k) {
        const float x=cx+(k-(keyCount-1)*.5f)*spacing;
        sculptedSlab(m,x,cz,keyWidth,length,0,depth,.021f,Ivory);
    }
    // Raised ebony levers sit behind the rolling lane. Their softened edges
    // reflect a long softbox instead of the hard rectangular glitter of cubes.
    for(int k=0;k<keyCount-1;++k) {
        const float x=cx+(k-(keyCount-2)*.5f)*spacing;
        sculptedSlab(m,x,(back-.305f)*.5f,spacing*.40f,-.305f-back,
                     -.062f,.060f,.014f,Ebony);
    }
    // A felt damper and a fine nickel lip give the tiny keyboard a real mechanism.
    // Both stay behind/below the tangent, so the marble never hits decoration.
    rod(m,{cx-width*.43f,.018f,back+.035f},{cx+width*.43f,.018f,back+.035f},.010f,Iron,8);
    for(int k=0;k<keyCount;++k) {
        const float x=cx+(k-(keyCount-1)*.5f)*spacing;
        rod(m,{x-keyWidth*.34f,.003f,-.255f},{x+keyWidth*.34f,.003f,-.255f},.005f,Copper,6);
    }
    rod(m,{cx-width*.43f,depth*.60f,front-.017f},{cx+width*.43f,depth*.60f,front-.017f},.007f,Chrome,8);
    // Open nickel carrier: no solid black box beneath the ivory keyboard.
    const float railY=depth+.017f;
    for(float x : {cx-width*.36f,cx+width*.36f})
        rod(m,{x,railY,back+.09f},{x,railY,front-.06f},.013f,Chrome,10);
    rod(m,{cx-width*.39f,railY,back+.115f},{cx+width*.39f,railY,back+.115f},.018f,Iron,10);
    for(auto& vertex:m.verts)
        vertex.detail=shortKey ? std::min(1.f,std::abs(vertex.p.x)/width) : 1.f;
    const float mountY=depth+.082f;
    // One delicate forked wall mounting supports the three sprung levers.
    ellipsoid(m,{cx,mountY,WallZ+.035f},{.061f,.061f,.030f},Chrome,14,7);
    rod(m,{cx,mountY,WallZ+.055f},{cx,mountY,-.43f},.023f,Chrome,12);
    rod(m,{cx-width*.29f,mountY,-.43f},{cx+width*.29f,mountY,-.43f},.021f,Chrome,10);
    for(float dx : {-width*.29f,width*.29f}) {
        rod(m,{cx+dx,railY,-.43f},{cx+dx,mountY,-.43f},.016f,Chrome,10);
        ellipsoid(m,{cx+dx,railY,-.43f},{.022f,.020f,.022f},Bolt,10,5);
    }
    return m;
}

// Small physical keyboard caps and rails use independent face normals, so their
// broad highlights do not round over the join as they would on a shared cube.
inline void box(Mesh& m, V low, V high, Surface top, Surface side) {
    const V corners[] = {{low.x,low.y,low.z}, {high.x,low.y,low.z},
                         {high.x,low.y,high.z}, {low.x,low.y,high.z},
                         {low.x,high.y,low.z}, {high.x,high.y,low.z},
                         {high.x,high.y,high.z}, {low.x,high.y,high.z}};
    const int faces[][4] = {{0,1,2,3}, {4,7,6,5}, {0,4,5,1}, {3,2,6,7}, {0,3,7,4}, {1,5,6,2}};
    const V normals[] = {{0,-1,0}, {0,1,0}, {0,0,-1}, {0,0,1}, {-1,0,0}, {1,0,0}};
    for (int f=0; f<6; ++f) {
        int a = static_cast<int>(m.verts.size());
        for (int i : faces[f]) m.point(corners[i],normals[f],f == 0 ? top : side);
        m.quad(a,a+1,a+2,a+3);
    }
}

// A real open cylinder, with a separate inner wall and annular ends. Its upper
// tangent is y=0 at z=0, exactly the same contact surface used by the trajectory.
inline Mesh percussionTube(bool shortKey, float side) {
    Mesh m;
    const float width = shortKey ? .46f : .70f, cx = keyCenterX(shortKey, side);
    const float radius = .103f, inner = radius-.014f, cy = radius;
    const float a = cx-width*.5f, b = cx+width*.5f;
    constexpr int sides = 24;
    for (int skin=0; skin<2; ++skin) {
        const float r = skin ? inner : radius;
        const float sign = skin ? -1.f : 1.f;
        const int begin = static_cast<int>(m.verts.size());
        for (float x : {a,b}) for (int i=0; i<sides; ++i) {
            const float angle = i*2.f*Pi/sides;
            V n{0,std::cos(angle),std::sin(angle)};
            m.point({x,cy+n.y*r,n.z*r},n*sign,skin ? TubeInside : TubePaint);
        }
        for (int i=0;i<sides;++i) m.quad(begin+i,begin+(i+1)%sides,begin+sides+(i+1)%sides,begin+sides+i);
    }
    for (int end=0;end<2;++end) {
        const int begin=static_cast<int>(m.verts.size());
        for (float r : {inner,radius}) for (int i=0;i<sides;++i) {
            const float angle=i*2.f*Pi/sides;
            m.point({end ? b : a,cy+std::cos(angle)*r,std::sin(angle)*r},
                    {end ? 1.f : -1.f,0,0},Chrome);
        }
        for (int i=0;i<sides;++i) m.quad(begin+i,begin+(i+1)%sides,begin+sides+(i+1)%sides,begin+sides+i);
    }
    for (auto& vertex:m.verts)
        vertex.detail=shortKey ? std::min(1.f,std::abs(vertex.p.x)/width) : 1.f;
    // Two rounded chrome saddles cradle the lower half without crossing the
    // playing tangent. The supports stay fixed while the pipe responds to a hit.
    const float mountY=.245f;
    for (float x : {cx-width*.30f,cx+width*.30f}) {
        std::vector<V> cradle;
        for (int i=0;i<=12;++i) {
            const float angle=-Pi*.5f+i*Pi/12;
            cradle.push_back({x,cy+std::cos(angle)*(radius+.010f),std::sin(angle)*(radius+.010f)});
        }
        tube(m,cradle,.010f,Chrome,6);
        rod(m,{x,mountY,WallZ+.04f},{x,mountY,-.035f},.017f,Chrome,8);
        rod(m,{x,mountY,-.035f},{x,cy+radius+.012f,0},.020f,Chrome,8);
        ellipsoid(m,{x,mountY,WallZ+.050f},{.046f,.046f,.026f},Bolt,10,5);
    }
    return m;
}

// A shallow tuned percussion pad, not a full-sized snare drum. The top membrane
// and its flush light ring share y=0, including the rail's inward landing lip.
inline Mesh percussionPad(bool shortKey, float side) {
    Mesh m;
    const float width = shortKey ? .46f : .70f, cx = keyCenterX(shortKey, side);
    const float rx = width*.5f, rz = .24f, depth = .18f;
    constexpr int sides = 32;
    auto ring = [&](float scale, float y, Surface material, bool face) {
        const int start = static_cast<int>(m.verts.size());
        for (int i=0;i<sides;++i) {
            const float a=2*Pi*i/sides, x=std::cos(a), z=std::sin(a);
            m.point({cx+x*rx*scale,y,z*rz*scale},face ? V{0,-1,0} : unit({x/rx,0,z/rz}),material);
        }
        return start;
    };
    const int center = m.point({cx,0,0},{0,-1,0},DrumSkin);
    const int skin = ring(.89f,0,DrumSkin,true);
    for (int i=0;i<sides;++i) m.tri(center,skin+i,skin+(i+1)%sides);
    const int inner = ring(.89f,0,LightRim,true), outer = ring(.955f,0,LightRim,true);
    const int rim = ring(.955f,0,Chrome,true), edge = ring(1.f,0,Chrome,true);
    for (int i=0;i<sides;++i) {
        const int n=(i+1)%sides;
        m.quad(inner+i,outer+i,outer+n,inner+n);
        m.quad(rim+i,edge+i,edge+n,rim+n);
    }
    const int top = ring(1.f,0,DrumShell,false), bottom = ring(.95f,depth,DrumShell,false);
    for (int i=0;i<sides;++i) m.quad(top+i,top+(i+1)%sides,bottom+(i+1)%sides,bottom+i);
    const int base=m.point({cx,depth,0},{0,1,0},DrumShell);
    for(int i=0;i<sides;++i) {
        const float a=2*Pi*i/sides;
        m.point({cx+std::cos(a)*rx*.95f,depth,std::sin(a)*rz*.95f},{0,1,0},DrumShell);
    }
    for(int i=0;i<sides;++i) m.tri(base,base+1+i,base+1+(i+1)%sides);
    // Six small tension fittings give the side a recognisable percussion silhouette.
    for (int i=0;i<6;++i) {
        const float a=2*Pi*i/6;
        const float x=cx+std::cos(a)*rx*.94f, z=std::sin(a)*rz*.94f;
        rod(m,{x,.037f,z},{x,.142f,z},.010f,Chrome,6);
    }
    for (auto& vertex:m.verts)
        vertex.detail=shortKey ? std::min(1.f,std::abs(vertex.p.x)/width) : 1.f;
    const float mountY=depth+.064f;
    rod(m,{cx,mountY,WallZ+.045f},{cx,mountY,-.075f},.023f,Chrome,10);
    rod(m,{cx,mountY,-.075f},{cx,depth,-.075f},.022f,Chrome,10);
    ellipsoid(m,{cx,mountY,WallZ+.035f},{.058f,.058f,.027f},Bolt,12,6);
    return m;
}

inline Mesh metalTongue(bool shortKey, float side) {
    Mesh m;
    const float width=shortKey ? .46f : .70f, cx=keyCenterX(shortKey,side);
    const float back=shortKey ? -.585f:-.66f, front=.25f, cz=(front+back)*.5f;
    sculptedSlab(m,cx,cz,width,front-back,0,.10f,.055f,Tongue);
    // The U-shaped cut is behind the rolling lane; its dark recessed line makes
    // the central vibrating tongue legible without obstructing the contact point.
    const float slot=width*.19f;
    for(float x : {cx-slot,cx+slot})
        rod(m,{x,-.001f,back+.09f},{x,-.001f,-.13f},.006f,Groove,6);
    std::vector<V> end;
    for(int i=0;i<=12;++i) {
        const float a=i*Pi/12;
        end.push_back({cx+std::cos(a)*slot,-.001f,-.13f+std::sin(a)*.06f});
    }
    tube(m,end,.006f,Groove,6);
    // Recessed fixing washers and an underside resonator mark the clamped end.
    for(float x:{cx-width*.30f,cx+width*.30f}) {
        rod(m,{x,.001f,back+.07f},{x,-.002f,back+.07f},.020f,Iron,10);
        rod(m,{x,-.002f,back+.07f},{x,-.005f,back+.07f},.010f,Bolt,8);
    }
    rod(m,{cx,.148f,back+.12f},{cx,.148f,front-.09f},.036f,Copper,12);
    for(auto& vertex:m.verts)
        vertex.detail=shortKey ? std::min(1.f,std::abs(vertex.p.x)/width) : 1.f;
    const float mountY=.205f;
    rod(m,{cx,mountY,WallZ+.045f},{cx,mountY,-.40f},.024f,Chrome,10);
    rod(m,{cx-width*.28f,mountY,-.40f},{cx+width*.28f,mountY,-.40f},.018f,Chrome,8);
    for(float x:{cx-width*.28f,cx+width*.28f})
        rod(m,{x,.11f,-.40f},{x,mountY,-.40f},.015f,Chrome,8);
    ellipsoid(m,{cx,mountY,WallZ+.035f},{.060f,.060f,.028f},Bolt,12,6);
    return m;
}

// Loft rounded rectangular contours. Bevel normals angle smoothly into the flat top.
// The two fasteners sit inside recessed ferrules on the playing face.
inline Mesh keyMesh(bool shortKey, float side, bool accented, const KeyDesign& design) {
    const int instrument = keyInstrument(design, accented);
    if (instrument == 2) return percussionTube(shortKey,side);
    if (instrument == 3) return percussionPad(shortKey,side);
    if (instrument == 4) return metalTongue(shortKey,side);
    if (instrument == 1) return keyboardMesh(shortKey,side,accented,design);
    Mesh m;
    const Surface playing = design.instrument == 1 ? Ivory : Wood;
    float width = shortKey ? .46f : .70f;
    // Keep the strike near the inward lip and the long part of the key behind
    // the track. The cap then stays clear of the ball on the next row below.
    float cx = keyCenterX(shortKey, side);
    float frontZ = .25f, backZ = shortKey ? -.585f : -.66f;
    float length = frontZ - backZ, cz = (frontZ + backZ) * .5f;
    const float depth = keyDepth(design,accented);
    const float corner = design.corner;
    const int perCorner = design.cornerSteps, ring = 4 * perCorner;
    struct Level {
        float y, inset, slope;
        Surface surface;
    };
    // A broad face, two cuts around the polished bevel, a narrow polymer
    // gasket, and the recessed gunmetal underbody are all inside the key bounds.
    const Level levels[] = {{0, .010f, 1, playing},
                            {.010f, .006f, .82f, design.rim},
                            {.024f, .002f, .41f, design.rim},
                            {.040f, 0, 0, playing},
                            {.052f, 0, 0, design.band},
                            {.066f, .009f, -.28f, design.rim},
                            {.083f, .026f, -.45f, design.instrument == 1 ? Ebony : Wood},
                            {depth - .018f, .026f, 0, design.instrument == 1 ? Ebony : Wood},
                            {depth, .043f, -.78f, Chrome}};
    auto contour = [&](Level l, int i) {
        int quadrant = i / perCorner;
        float angle = (quadrant + (i % perCorner) / float(perCorner - 1)) * Pi * .5f;
        float nx = std::cos(angle), nz = std::sin(angle);
        float x = cx + (quadrant == 0 || quadrant == 3 ? 1 : -1) * (width * .5f - corner);
        float z = cz + (quadrant < 2 ? 1 : -1) * (length * .5f - corner);
        return V{x + (corner - l.inset) * nx, l.y, z + (corner - l.inset) * nz};
    };
    int top = m.point({cx, 0, cz}, {0, -1, 0}, playing);
    for (int i = 0; i < ring; ++i)
        m.point(contour(levels[0], i), {0, -1, 0}, playing);
    for (int i = 0; i < ring; ++i)
        m.tri(top, top + 1 + i, top + 1 + (i + 1) % ring);
    for (int j = 0; j < 8; ++j) {
        int base = static_cast<int>(m.verts.size());
        for (int k = 0; k < 2; ++k)
            for (int i = 0; i < ring; ++i) {
                Level l = levels[j + k];
                float a = (i / perCorner + (i % perCorner) / float(perCorner - 1)) * Pi * .5f;
                float f = std::sqrt(1 - l.slope * l.slope);
                m.point(contour(l, i), {std::cos(a) * f, -l.slope, std::sin(a) * f}, levels[j + 1].surface);
            }
        for (int i = 0; i < ring; ++i) {
            int a = base + i, b = base + (i + 1) % ring;
            m.quad(a, b, b + ring, a + ring);
        }
    }
    int bottom = m.point({cx, depth, cz}, {0, 1, 0}, Skirt);
    for (int i = 0; i < ring; ++i)
        m.point(contour(levels[8], i), {0, 1, 0}, Skirt);
    for (int i = 0; i < ring; ++i)
        m.tri(bottom, bottom + 1 + i, bottom + 1 + (i + 1) % ring);
    if (design.instrument == 1) {
        // Three ivory levers and two raised black keys. The short black caps are
        // entirely behind the marble lane, leaving the strike plane untouched.
        for (float x : {cx-width/6.f,cx+width/6.f}) {
            box(m,{x-.003f,-.001f,backZ+.032f},{x+.003f,.003f,frontZ-.034f},Ebony,Ebony);
            const float half=width*.063f;
            box(m,{x-half,-.067f,backZ+.036f},{x+half,-.002f,-.295f},Ebony,Ebony);
            box(m,{x-half+.006f,-.069f,backZ+.046f},{x+half-.006f,-.066f,-.309f},Ebony,Ebony);
        }
        // A polished front lip catches the large studio softbox.
        rod(m,{cx-width*.39f,.036f,frontZ-.017f},{cx+width*.39f,.036f,frontZ-.017f},.007f,Chrome,6);
    } else {
        // The two marimba suspension nodes are bored through the wood and
        // edged with a small dark felt washer, rather than a decorative LED.
        for (float z : {backZ+.14f,frontZ-.13f}) {
            rod(m,{cx,.002f,z},{cx,-.002f,z},.024f,Iron,10);
            rod(m,{cx,-.002f,z},{cx,-.005f,z},.012f,Bolt,10);
        }
        // A slim resonator under the bar adds a recognisable marimba silhouette.
        rod(m,{cx,depth+.040f,backZ+.13f},{cx,depth+.040f,frontZ-.09f},.045f,Copper,12);
    }
    // Only the cap and its fasteners travel. Guided caps hinge at the rail lip
    // to keep the transfer surface tangent while the rear of the cap goes down.
    for (auto& vertex : m.verts)
        vertex.detail = shortKey ? std::min(1.f, std::abs(vertex.p.x) / width) : 1.f;
    // One turned wall boss carries a forked sliding carriage; these parts stay
    // attached to the wall throughout the short motion of the striking cap.
    float mountY = depth + .092f;
    rod(m, {cx, mountY, WallZ + .010f}, {cx, mountY, WallZ + .055f}, .090f, Iron, 14);
    rod(m, {cx, mountY, WallZ + .040f}, {cx, mountY, WallZ + .115f}, .073f, Chrome, 14);
    rod(m, {cx, mountY, WallZ + .090f}, {cx, mountY, -.41f}, .032f, Chrome, 10);
    rod(m, {cx - .112f, mountY, -.41f}, {cx + .112f, mountY, -.41f}, .041f, Bolt, 10);
    for (float dx : {-.088f, .088f})
        rod(m, {cx + dx, depth - .012f, -.41f}, {cx + dx, mountY, -.41f}, .026f, Chrome);
    return m;
}

inline Mesh railMesh(const std::vector<V>& input, bool launcher) {
    Mesh m;
    if (input.size() < 2)
        return m;
    // Samples are positions of the sphere centre. The distance to each rail axis
    // along the local normal follows from the sphere/rail radii and track gauge.
    constexpr float gauge = .112f, radius = .022f;
    const float offset = std::sqrt((BallRadius + radius) * (BallRadius + radius) - gauge * gauge);
    std::vector<V> center, left, right, spine;
    center.reserve(input.size());
    for (size_t i = 0; i < input.size(); ++i) {
        V t = unit(input[std::min(i + 1, input.size() - 1)] - input[i ? i - 1 : 0]);
        float sign = t.x < 0 ? -1.f : 1.f;
        center.push_back(input[i] + V{-t.y * sign, t.x * sign, 0} * offset);
        left.push_back(center.back() + V{0, 0, -gauge});
        right.push_back(center.back() + V{0, 0, gauge});
        // A continuous box spine supports the cross-ties below the two runners.
        spine.push_back(center.back() + V{0, .090f, 0});
    }
    if (launcher) {
        // A padded backstop closes the loading end behind the seated ball.
        V tangent = unit(input[1] - input[0]);
        V foot = center.front() - tangent * .267f;
        V stop = input.front() - tangent * .267f;
        // The launch magnet holds the seated marble through a rest. Its pole
        // face and copper windings sit below the runners, clear of the sphere.
        V down = unit(center.front() - input.front());
        V pole = input.front() + down * .265f;
        rod(m, pole, pole + down * .19f, .142f, Iron, 14);
        for (float inset : {.043f, .078f, .113f})
            rod(m, pole + down * inset, pole + down * (inset + .022f), .150f, Copper, 14);
        V mount = pole + down * .17f;
        rod(m, mount, {mount.x, mount.y, WallZ + .035f}, .040f, Iron);
        for (float z : {-gauge, gauge}) {
            V side{0, 0, z};
            rod(m, foot + side, center.front() + side, radius, Rail);
            rod(m, foot + side, stop + side, .020f, Chrome);
        }
        rod(m, stop + V{0, 0, -.161f}, stop + V{0, 0, .161f}, .036f, Iron, 12);
    }
    tube(m, spine, .046f, Iron, 4);
    tube(m, left, radius, Rail, 8);
    tube(m, right, radius, Rail, 8);
    // Seat each runner on its cross-tie. The short shoes sit below the rolling surface.
    auto shoes = [&](V c) {
        for (float z : {-gauge, gauge})
            rod(m, c + V{0, .012f, z}, c + V{0, .052f, z}, .014f, Iron, 6);
    };
    float dist = 0;
    for (size_t i = 1; i < center.size(); ++i) {
        V delta = center[i] - center[i - 1];
        dist += std::sqrt(dot(delta, delta));
        if (dist < .58f && i + 1 < center.size())
            continue;
        dist = 0;
        V a = center[i] + V{0, .052f, gauge + .03f};
        V b = a + V{0, 0, -.32f}, c = b + V{0, -.07f, -.09f};
        V d{c.x, c.y, WallZ + .035f};
        rod(m, a, b, .023f, Iron, 8);
        tube(m, {b, c, d}, .018f, Iron, 7);
        shoes(center[i]);
        rod(m, {d.x, d.y, WallZ + .018f}, {d.x, d.y, WallZ + .055f}, .034f, Bolt, 9);
    }
    return m;
}
} // namespace cascade

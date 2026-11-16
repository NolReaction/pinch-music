// Local-coordinate triangle meshes shared by the Android and iOS renderers.
#pragma once
#include <algorithm>
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
enum Surface { Enamel, Skirt, Inlay, Chrome, Iron, Bolt, Accent, Rail, Glass, Copper };
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

// A circular cross section follows the tangent; the cap normals are independent.
inline void tube(Mesh& m, const std::vector<V>& path, float radius, Surface surface, int sides = 8,
                 float detail = 0) {
    if (path.size() < 2)
        return;
    const int begin = static_cast<int>(m.verts.size());
    const V axis = unit(path.back() - path.front());
    const V guide = std::abs(axis.z) < .9f ? V{0, 0, 1} : V{0, 1, 0};
    for (size_t j = 0; j < path.size(); ++j) {
        V t = unit(path[std::min(j + 1, path.size() - 1)] - path[j ? j - 1 : 0]);
        V b = unit(cross(t, guide)), c = cross(b, t);
        for (int k = 0; k < sides; ++k) {
            float a = k * 2.f * Pi / sides;
            V n = b * std::cos(a) + c * std::sin(a);
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

inline float keyCenterX(bool shortKey, float side) {
    return side * (shortKey ? .23f : .22f);
}

// Loft rounded rectangular contours. Bevel normals angle smoothly into the flat top.
// The two fasteners sit inside recessed ferrules on the playing face.
inline Mesh keyMesh(bool shortKey, float side, bool accented) {
    Mesh m;
    float width = shortKey ? .46f : .70f;
    // Keep the strike near the inward lip and the long part of the key behind
    // the track. The cap then stays clear of the ball on the next row below.
    float cx = keyCenterX(shortKey, side);
    float frontZ = .25f, backZ = shortKey ? -.585f : -.66f;
    float length = frontZ - backZ, cz = (frontZ + backZ) * .5f;
    float depth = accented ? .36f : .215f;
    const float corner = .115f;
    const int perCorner = 4, ring = 4 * perCorner;
    struct Level {
        float y, inset, slope;
        Surface surface;
    };
    const Level levels[] = {{0, .041f, 1, Enamel},
                            {.010f, .018f, .72f, Enamel},
                            {.030f, .003f, .28f, accented ? Inlay : Enamel},
                            {.041f, 0, 0, accented ? Inlay : Enamel},
                            {.053f, 0, 0, Inlay},
                            {.065f, 0, 0, Inlay},
                            {depth - .03f, 0, 0, Skirt},
                            {depth - .009f, .011f, -.6f, Skirt},
                            {depth, .035f, -1, Skirt}};
    auto contour = [&](Level l, int i) {
        int quadrant = i / perCorner;
        float angle = (quadrant + (i % perCorner) / float(perCorner - 1)) * Pi * .5f;
        float nx = std::cos(angle), nz = std::sin(angle);
        float x = cx + (quadrant == 0 || quadrant == 3 ? 1 : -1) * (width * .5f - corner);
        float z = cz + (quadrant < 2 ? 1 : -1) * (length * .5f - corner);
        return V{x + (corner - l.inset) * nx, l.y, z + (corner - l.inset) * nz};
    };
    int top = m.point({cx, 0, cz}, {0, -1, 0}, Enamel);
    for (int i = 0; i < ring; ++i)
        m.point(contour(levels[0], i), {0, -1, 0}, Enamel);
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
    for (float z : {backZ + .14f, frontZ - .14f}) {
        rod(m, {cx, .002f, z}, {cx, -.009f, z}, .045f, Iron, 8);
        ellipsoid(m, {cx, -.009f, z}, {.033f, .021f, .033f}, Bolt, 8, 4);
    }
    if (accented) {
        const float z = frontZ + .0015f;
        // Rounded luminous gasket wrapping the end cap.
        for (float x : {cx - width * .5f + .076f, cx + width * .5f - .076f})
            rod(m, {x, .049f, z - .009f}, {x, depth - .037f, z - .009f}, .014f, Accent, 6);
        rod(m, {cx - width * .5f + .076f, depth - .037f, z - .009f},
            {cx + width * .5f - .076f, depth - .037f, z - .009f}, .014f, Accent, 6);
    }
    // Only the cap and its fasteners travel. Guided caps hinge at the rail lip
    // to keep the transfer surface tangent while the rear of the cap goes down.
    for (auto& vertex : m.verts)
        vertex.detail = shortKey ? std::min(1.f, std::abs(vertex.p.x) / width) : 1.f;
    for (float dx : {-.105f, .105f}) {
        float x = cx + dx;
        rod(m, {x, depth - .012f, -.32f}, {x, depth + .09f, -.32f}, .029f, Chrome);
        rod(m, {x, depth + .09f, -.32f}, {x, depth + .09f, WallZ + .025f}, .030f, Chrome);
        rod(m, {x, depth + .09f, WallZ + .014f}, {x, depth + .09f, WallZ + .065f}, .050f, Iron, 8);
        rod(m, {x, depth + .09f, WallZ + .056f}, {x, depth + .09f, WallZ + .077f}, .033f, Bolt, 8);
    }
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

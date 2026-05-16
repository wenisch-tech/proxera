/**
 * topology.js — Modern dark-glass topology for Proxera.
 * Left-to-right hierarchy: Server → Clients → Routes
 * Glowing glass cards · Gradient bezier edges · Animated flow particles
 */

// ── Card dimensions ────────────────────────────────────────────────────────────
const CW = 172;   // card width (px)
const CH = 82;    // card height (px)
const CR = 11;    // corner radius
const IW = 52;    // left icon-zone width

// ── Column fractions of canvas width ──────────────────────────────────────────
const COL_X     = { server: 0.12, agent: 0.47, route: 0.83 };
const COL_LABEL = { server: 'PROXY SERVER', agent: 'AGENTS', route: 'ROUTES' };
const MIN_GAP   = 118;   // minimum vertical pitch between nodes (px)

let svg, _defs, linkLayer, nodeLayer, canvasW, canvasH;

// ── Color palette per node state ───────────────────────────────────────────────
function palette(d) {
    if (d.type === 'server')
        return { accent: '#818cf8', glow: '#4338ca', border: 'rgba(129,140,248,0.55)', muted: '#c7d2fe' };
    if (d.type === 'agent' && d.connected)
        return { accent: '#34d399', glow: '#059669', border: 'rgba(52,211,153,0.50)', muted: '#a7f3d0' };
    if (d.type === 'agent')
        return { accent: '#64748b', glow: '#1e293b', border: 'rgba(100,116,139,0.38)', muted: '#94a3b8' };
    if (d.enabled !== false)
        return { accent: '#fbbf24', glow: '#b45309', border: 'rgba(251,191,36,0.45)', muted: '#fde68a' };
    return { accent: '#64748b', glow: '#1e293b', border: 'rgba(100,116,139,0.38)', muted: '#94a3b8' };
}

function metaLabel(d) {
    const trim = (s, n) => s.length > n ? s.slice(0, n - 1) + '\u2026' : s;
    if (d.type === 'server') return 'Reverse Proxy  \u00b7  Active';
    if (d.type === 'agent') return d.connected ? '\u25cf Online' : '\u25cb Offline';
    if (d.target) return '\u2192 ' + trim(String(d.target), 18);
    return d.enabled !== false ? '\u25cf Enabled' : '\u25cb Disabled';
}

// ── Icon drawing, centered at (0,0) ───────────────────────────────────────────
function drawIcon(g, d) {
    const c = palette(d).accent;

    if (d.type === 'server') {
        // Two server rack slots
        [-8, 2].forEach(yo => {
            g.append('rect').attr('x', -11).attr('y', yo).attr('width', 22).attr('height', 8)
                .attr('rx', 2).attr('fill', 'none').attr('stroke', c).attr('stroke-width', 1.5);
            g.append('circle').attr('cx', -8).attr('cy', yo + 4).attr('r', 1.6).attr('fill', c);
            g.append('rect').attr('x', -3).attr('y', yo + 2).attr('width', 9).attr('height', 2)
                .attr('rx', 1).attr('fill', c).attr('opacity', 0.6);
        });
    } else if (d.type === 'agent') {
        // Monitor + stand
        g.append('rect').attr('x', -11).attr('y', -9).attr('width', 22).attr('height', 15)
            .attr('rx', 2).attr('fill', 'none').attr('stroke', c).attr('stroke-width', 1.5);
        g.append('rect').attr('x', -9).attr('y', -7).attr('width', 18).attr('height', 11)
            .attr('rx', 1).attr('fill', c).attr('opacity', 0.12);
        g.append('line').attr('x1', -11).attr('y1', 6).attr('x2', 11).attr('y2', 6)
            .attr('stroke', c).attr('stroke-width', 1.5);
        g.append('line').attr('x1', 0).attr('y1', 6).attr('x2', 0).attr('y2', 10)
            .attr('stroke', c).attr('stroke-width', 1.5);
        g.append('line').attr('x1', -4).attr('y1', 10).attr('x2', 4).attr('y2', 10)
            .attr('stroke', c).attr('stroke-width', 1.5);
    } else {
        // Signpost
        g.append('line').attr('x1', 0).attr('y1', -10).attr('x2', 0).attr('y2', 10)
            .attr('stroke', c).attr('stroke-width', 1.6);
        g.append('path').attr('d', 'M0,-9 L8,-9 L11,-5 L8,-1 L0,-1 Z')
            .attr('fill', c).attr('opacity', 0.28).attr('stroke', c).attr('stroke-width', 1.2);
        g.append('path').attr('d', 'M0,1 L-8,1 L-11,5 L-8,9 L0,9 Z')
            .attr('fill', c).attr('opacity', 0.28).attr('stroke', c).attr('stroke-width', 1.2);
    }
}

// ── Column layout ──────────────────────────────────────────────────────────────
function computeLayout(nodes) {
    const groups = { server: [], agent: [], route: [] };
    nodes.forEach(n => { if (groups[n.type]) groups[n.type].push(n); });
    Object.entries(groups).forEach(([type, group]) => {
        if (!group.length) return;
        const cx   = COL_X[type] * canvasW;
        const span = (group.length - 1) * MIN_GAP;
        const top  = canvasH / 2 - span / 2;
        group.forEach((n, i) => { n.x = cx; n.y = top + i * MIN_GAP; });
    });
}

// ── Edge S-curve: exits right-center of src, enters left-center of tgt ────────
function edgePath(src, tgt) {
    const x1 = src.x + CW / 2,  y1 = src.y;
    const x2 = tgt.x - CW / 2,  y2 = tgt.y;
    const bend = Math.abs(x2 - x1) * 0.44;
    return `M${x1},${y1} C${x1 + bend},${y1} ${x2 - bend},${y2} ${x2},${y2}`;
}

// ── SVG initialisation ─────────────────────────────────────────────────────────
function initGraph() {
    const el = document.getElementById('topology-canvas');
    canvasW = el.clientWidth  || el.getBoundingClientRect().width  || 920;
    canvasH = el.clientHeight || el.getBoundingClientRect().height || 560;

    svg = d3.select('#topology-canvas').append('svg')
        .attr('width', '100%').attr('height', canvasH);

    _defs = svg.append('defs');

    // Card drop shadow
    const sh = _defs.append('filter').attr('id', 'card-shadow')
        .attr('x', '-25%').attr('y', '-45%').attr('width', '150%').attr('height', '190%');
    sh.append('feDropShadow').attr('dx', 0).attr('dy', 6).attr('stdDeviation', 14)
        .attr('flood-color', '#000').attr('flood-opacity', 0.70);

    // Ambient glow (applied to blurred ellipse behind card)
    const ag = _defs.append('filter').attr('id', 'amb-glow')
        .attr('x', '-90%').attr('y', '-110%').attr('width', '280%').attr('height', '320%');
    ag.append('feGaussianBlur').attr('stdDeviation', 24);

    // Link glow (for pulsing active edges)
    const lg = _defs.append('filter').attr('id', 'link-glow')
        .attr('x', '-100%').attr('y', '-600%').attr('width', '300%').attr('height', '1300%');
    lg.append('feGaussianBlur').attr('stdDeviation', 5).attr('result', 'blur');
    const lgm = lg.append('feMerge');
    lgm.append('feMergeNode').attr('in', 'blur');
    lgm.append('feMergeNode').attr('in', 'SourceGraphic');

    // Fine grid background
    const grid = _defs.append('pattern').attr('id', 'bg-grid')
        .attr('width', 36).attr('height', 36).attr('patternUnits', 'userSpaceOnUse');
    grid.append('path').attr('d', 'M 36 0 L 0 0 0 36')
        .attr('fill', 'none').attr('stroke', 'rgba(255,255,255,0.030)').attr('stroke-width', 1);
    svg.append('rect').attr('width', '100%').attr('height', '100%').attr('fill', 'url(#bg-grid)');

    // Radial vignette with subtle blue center warmth
    const vig = _defs.append('radialGradient').attr('id', 'bg-vig')
        .attr('cx', '50%').attr('cy', '50%').attr('r', '68%');
    vig.append('stop').attr('offset', '0%').attr('stop-color', 'rgba(59,130,246,0.022)');
    vig.append('stop').attr('offset', '100%').attr('stop-color', 'rgba(0,0,0,0.38)');
    svg.append('rect').attr('width', '100%').attr('height', '100%').attr('fill', 'url(#bg-vig)');

    linkLayer = svg.append('g').attr('class', 'links');
    nodeLayer = svg.append('g').attr('class', 'nodes');
}

// ── Main render ───────────────────────────────────────────────────────────────
function renderGraph(data) {
    const nodes = data.nodes;
    const links = data.links;
    if (!nodes.length) return;

    computeLayout(nodes);

    const byId = {};
    nodes.forEach(n => { byId[n.id] = n; });

    const edges = links.map(l => ({
        id:   `${l.source.id || l.source}-${l.target.id || l.target}`,
        type: l.type,
        src:  byId[l.source.id || l.source],
        tgt:  byId[l.target.id || l.target]
    })).filter(e => e.src && e.tgt);

    // Clear previous render (dynamic defs, links, nodes, labels)
    _defs.selectAll('.dyn').remove();
    linkLayer.selectAll('*').remove();
    nodeLayer.selectAll('*').remove();
    svg.selectAll('.col-label, .col-div').remove();

    // ── Column labels ──────────────────────────────────────────────────────────
    const seen = new Set(nodes.map(n => n.type));
    Object.entries(COL_LABEL).forEach(([type, label]) => {
        if (!seen.has(type)) return;
        svg.append('text').attr('class', 'col-label')
            .attr('x', COL_X[type] * canvasW).attr('y', 24)
            .attr('text-anchor', 'middle')
            .attr('font-size', '7.5px').attr('font-weight', '700').attr('letter-spacing', '.20em')
            .attr('fill', 'rgba(255,255,255,0.14)').attr('font-family', 'Inter, system-ui, sans-serif')
            .text(label);
    });

    // Column dividers
    [(COL_X.server + COL_X.agent) / 2 * canvasW, (COL_X.agent + COL_X.route) / 2 * canvasW]
        .forEach(x => {
            svg.append('line').attr('class', 'col-div')
                .attr('x1', x).attr('y1', 40).attr('x2', x).attr('y2', canvasH - 24)
                .attr('stroke', 'rgba(255,255,255,0.04)').attr('stroke-width', 1)
                .attr('stroke-dasharray', '3,9');
        });

    // ── Edges ──────────────────────────────────────────────────────────────────
    edges.forEach((e, i) => {
        const isTunnel = e.type === 'tunnel';
        const sc = palette(e.src).accent;
        const tc = palette(e.tgt).accent;
        const gid = `eg${i}`;

        // Per-edge gradient in canvas userspace
        _defs.append('linearGradient').attr('class', 'dyn').attr('id', gid)
            .attr('gradientUnits', 'userSpaceOnUse')
            .attr('x1', e.src.x + CW / 2).attr('y1', e.src.y)
            .attr('x2', e.tgt.x - CW / 2).attr('y2', e.tgt.y)
            .call(gr => {
                gr.append('stop').attr('offset', '0%').attr('stop-color', sc).attr('stop-opacity', 0.75);
                gr.append('stop').attr('offset', '100%').attr('stop-color', tc).attr('stop-opacity', 0.75);
            });

        const d = edgePath(e.src, e.tgt);

        // Broad glow halo
        linkLayer.append('path').attr('d', d).attr('fill', 'none')
            .attr('stroke', `url(#${gid})`).attr('stroke-width', 10).attr('stroke-opacity', 0.12)
            .attr('stroke-linecap', 'round');

        // Main line
        linkLayer.append('path').datum(e).attr('class', 'topo-link')
            .attr('d', d).attr('fill', 'none')
            .attr('stroke', `url(#${gid})`).attr('stroke-width', 1.6)
            .attr('stroke-dasharray', isTunnel ? null : '6,5')
            .attr('stroke-linecap', 'round').attr('stroke-opacity', 0.88);

        // Animated particle dot (tunnel only)
        if (isTunnel) {
            linkLayer.append('path').datum(e).attr('class', 'edge-flow')
                .attr('d', d).attr('fill', 'none')
                .attr('stroke', tc).attr('stroke-width', 2.5)
                .attr('stroke-dasharray', '5,65').attr('stroke-linecap', 'round')
                .attr('stroke-opacity', 0.9);
        }
    });

    // ── Nodes ──────────────────────────────────────────────────────────────────
    nodes.forEach((d, i) => {
        const col = palette(d);
        const g = nodeLayer.append('g').attr('class', 'node')
            .attr('transform', `translate(${d.x - CW / 2},${d.y - CH / 2})`)
            .style('opacity', 0);

        g.transition().duration(420).delay(i * 55).style('opacity', 1);

        // ① Ambient glow blob behind card
        g.append('ellipse')
            .attr('cx', CW / 2).attr('cy', CH / 2)
            .attr('rx', CW * 0.52).attr('ry', CH * 0.65)
            .attr('fill', col.glow).attr('opacity', 0.30)
            .attr('filter', 'url(#amb-glow)');

        // ② Card dark background
        g.append('rect').attr('width', CW).attr('height', CH).attr('rx', CR)
            .attr('fill', 'rgba(8,14,26,0.90)').attr('filter', 'url(#card-shadow)');

        // ③ Icon zone: gradient fading from accent color to transparent
        const izgId  = `izg${i}`;
        const clipId = `clip${i}`;
        _defs.append('clipPath').attr('class', 'dyn').attr('id', clipId)
            .append('rect').attr('width', CW).attr('height', CH).attr('rx', CR);
        _defs.append('linearGradient').attr('class', 'dyn').attr('id', izgId)
            .attr('x1', '0%').attr('x2', '100%').attr('y1', '0%').attr('y2', '0%')
            .call(gr => {
                gr.append('stop').attr('offset', '0%').attr('stop-color', col.glow).attr('stop-opacity', 0.32);
                gr.append('stop').attr('offset', '100%').attr('stop-color', col.glow).attr('stop-opacity', 0);
            });
        g.append('rect').attr('width', IW).attr('height', CH)
            .attr('fill', `url(#${izgId})`).attr('clip-path', `url(#${clipId})`);

        // ④ Vertical separator between icon zone and text zone
        g.append('line')
            .attr('x1', IW).attr('y1', 14).attr('x2', IW).attr('y2', CH - 14)
            .attr('stroke', col.border).attr('stroke-width', 1);

        // ⑤ Colored border
        g.append('rect').attr('width', CW).attr('height', CH).attr('rx', CR)
            .attr('fill', 'none').attr('stroke', col.accent).attr('stroke-width', 1.25)
            .attr('stroke-opacity', 0.52);

        // ⑥ Status dot (top-right corner)
        const active = d.type === 'server'
            || (d.type === 'agent' && d.connected)
            || (d.type === 'route'  && d.enabled !== false);
        g.append('circle').attr('cx', CW - 15).attr('cy', 15).attr('r', 3.5)
            .attr('fill', active ? col.accent : '#334155').attr('opacity', 0.95);
        if (active) {
            g.append('circle').attr('cx', CW - 15).attr('cy', 15).attr('r', 6.5)
                .attr('fill', 'none').attr('stroke', col.accent).attr('stroke-width', 1)
                .attr('stroke-opacity', 0.38);
        }

        // ⑦ Icon centered in the icon zone
        g.append('g').attr('transform', `translate(${IW / 2},${CH / 2})`)
            .each(function() { drawIcon(d3.select(this), d); });

        // ⑧ Node name
        const name = d.name.length > 15 ? d.name.slice(0, 13) + '\u2026' : d.name;
        g.append('text')
            .attr('x', IW + 12).attr('y', CH / 2 - 6)
            .attr('font-size', '11.5px').attr('font-weight', '600')
            .attr('font-family', 'Inter, system-ui, sans-serif').attr('fill', '#e2e8f0')
            .text(name);

        // ⑨ Meta label (status / target)
        g.append('text')
            .attr('x', IW + 12).attr('y', CH / 2 + 10)
            .attr('font-size', '9.5px').attr('font-family', 'Inter, system-ui, sans-serif')
            .attr('fill', col.muted)
            .text(metaLabel(d));

        // ⑩ Subtle type badge (bottom-right)
        g.append('text')
            .attr('x', CW - 10).attr('y', CH - 8).attr('text-anchor', 'end')
            .attr('font-size', '7px').attr('letter-spacing', '.10em')
            .attr('font-family', 'Inter, system-ui, sans-serif')
            .attr('fill', 'rgba(255,255,255,0.16)')
            .text(d.type.toUpperCase());
    });
}

// ── Public API ─────────────────────────────────────────────────────────────────
function refreshTopology() {
    fetch('/admin/topology/data')
        .then(r => r.json())
        .then(data => {
            if (!svg) initGraph();
            renderGraph(data);
        });
}

function pulseLink(event) {
    linkLayer.selectAll('path.topo-link')
        .filter(function(d) {
            return d && (d.src?.id === event.agentId || d.tgt?.id === event.agentId);
        })
        .classed('link-pulsing', true).attr('filter', 'url(#link-glow)');
}

function clearPulse(event) {
    linkLayer.selectAll('path.topo-link')
        .filter(function(d) {
            return d && (d.src?.id === event.agentId || d.tgt?.id === event.agentId);
        })
        .classed('link-pulsing', false).attr('filter', null);
}

/**
 * topology.js — Modern dark-glass topology for Proxera.
 * Full-viewport interactive graph: Server → Agents → Routes
 * Glowing glass cards · Gradient bezier edges · Animated flow particles
 * Click any node to open the slide-in management panel.
 */

// ── Card dimensions ────────────────────────────────────────────────────────────
const CW = 172;   // card width (px)
const CH = 82;    // card height (px)
const CR = 11;    // corner radius
const IW = 52;    // left icon-zone width

// ── CSS-var reader: picks up current theme at render time ────────────────────
function tv(name) {
    return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}

// ── Column fractions of canvas width ──────────────────────────────────────────
const COL_X     = { server: 0.12, agent: 0.45, route: 0.80 };
const ROW_Y     = { server: 0.15, agent: 0.48, route: 0.82 };  // portrait fractions of canvasH
const COL_LABEL = { server: 'PROXY SERVER', agent: 'AGENTS', route: 'ROUTES' };
const MIN_GAP   = 118;   // vertical pitch between nodes in landscape (px)
const MIN_GAP_H = 188;   // horizontal pitch between nodes in portrait (px)

function isPortrait() { return canvasW < 700; }

let svg, _defs, linkLayer, nodeLayer, canvasW, canvasH;

// Keep the last topology data for panel usage and theme re-rendering
let _lastNodes = [];
let _lastLinks = [];
let _lastData  = { nodes: [], links: [] };

// ── Color palette per node state ───────────────────────────────────────────────
function palette(d) {
    if (d.type === 'server')
        return { accent: '#818cf8', glow: '#4338ca', border: 'rgba(129,140,248,0.55)', muted: '#c7d2fe' };
    if (d.type === 'agent' && d.connected)
        return { accent: '#34d399', glow: '#059669', border: 'rgba(52,211,153,0.50)', muted: '#a7f3d0' };
    if (d.type === 'agent')
        return { accent: '#64748b', glow: '#1e293b', border: 'rgba(100,116,139,0.38)', muted: '#94a3b8' };
    if (d.type === 'add-agent' || d.type === 'add-route')
        return { accent: '#475569', glow: '#1e293b', border: 'rgba(71,85,105,0.35)', muted: '#64748b' };
    if (d.enabled !== false)
        return { accent: '#fbbf24', glow: '#b45309', border: 'rgba(251,191,36,0.45)', muted: '#fde68a' };
    return { accent: '#64748b', glow: '#1e293b', border: 'rgba(100,116,139,0.38)', muted: '#94a3b8' };
}

function metaLabel(d) {
    const trim = (s, n) => s.length > n ? s.slice(0, n - 1) + '\u2026' : s;
    if (d.type === 'server')    return 'Reverse Proxy  \u00b7  Active';
    if (d.type === 'add-agent') return 'Click to create';
    if (d.type === 'add-route') return 'Click to create';
    if (d.type === 'agent')     return d.connected ? '\u25cf Online' : '\u25cb Offline';
    if (d.target)               return '\u2192 ' + trim(String(d.target), 18);
    return d.enabled !== false ? '\u25cf Enabled' : '\u25cb Disabled';
}

// ── Icon drawing, centered at (0,0) ───────────────────────────────────────────
function drawIcon(g, d) {
    const c = palette(d).accent;

    if (d.type === 'add-agent' || d.type === 'add-route') {
        // Big "+" icon
        g.append('line').attr('x1', 0).attr('y1', -9).attr('x2', 0).attr('y2', 9)
            .attr('stroke', c).attr('stroke-width', 2.2).attr('stroke-linecap', 'round');
        g.append('line').attr('x1', -9).attr('y1', 0).attr('x2', 9).attr('y2', 0)
            .attr('stroke', c).attr('stroke-width', 2.2).attr('stroke-linecap', 'round');
        return;
    }

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

// ── Column layout (includes placeholder nodes) ─────────────────────────────────
function computeLayout(nodes) {
    const groups = { server: [], agent: [], route: [], 'add-agent': [], 'add-route': [] };
    nodes.forEach(n => { if (groups[n.type]) groups[n.type].push(n); });

    if (isPortrait()) {
        // Portrait: rows (top → bottom), nodes spread horizontally within each row
        ['server', 'agent', 'route'].forEach(type => {
            const group = groups[type];
            if (!group.length) return;
            const cy   = ROW_Y[type] * canvasH;
            const span = (group.length - 1) * MIN_GAP_H;
            const left = canvasW / 2 - span / 2;
            group.forEach((n, i) => { n.x = left + i * MIN_GAP_H; n.y = cy; });
        });
        // Placeholder nodes to the right of the last real node in their row
        ['add-agent', 'add-route'].forEach(type => {
            const rowType = type === 'add-agent' ? 'agent' : 'route';
            const real = groups[rowType];
            const placeholder = groups[type];
            if (!placeholder.length) return;
            const cy   = ROW_Y[rowType] * canvasH;
            const lastX = real.length ? real[real.length - 1].x + MIN_GAP_H : canvasW / 2;
            placeholder.forEach((n, i) => { n.x = lastX + i * MIN_GAP_H; n.y = cy; });
        });
        return;
    }

    // Landscape: columns (left → right)
    ['server', 'agent', 'route'].forEach(type => {
        const group = groups[type];
        if (!group.length) return;
        const cx   = COL_X[type] * canvasW;
        const span = (group.length - 1) * MIN_GAP;
        const top  = canvasH / 2 - span / 2;
        group.forEach((n, i) => { n.x = cx; n.y = top + i * MIN_GAP; });
    });

    // Position placeholder nodes below the last real node of that column
    ['add-agent', 'add-route'].forEach(type => {
        const colType = type === 'add-agent' ? 'agent' : 'route';
        const real = groups[colType];
        const placeholder = groups[type];
        if (!placeholder.length) return;
        const cx = COL_X[colType] * canvasW;
        const lastY = real.length
            ? real[real.length - 1].y + MIN_GAP
            : canvasH / 2;
        placeholder.forEach((n, i) => {
            n.x = cx;
            n.y = lastY + i * MIN_GAP;
        });
    });
}

// ── Edge S-curve ──────────────────────────────────────────────────────────────
// Portrait:  exits bottom-center of src, enters top-center of tgt
// Landscape: exits right-center of src, enters left-center of tgt
function edgePath(src, tgt) {
    if (isPortrait()) {
        const x1 = src.x,        y1 = src.y + CH / 2;
        const x2 = tgt.x,        y2 = tgt.y - CH / 2;
        const bend = Math.abs(y2 - y1) * 0.44;
        return `M${x1},${y1} C${x1},${y1 + bend} ${x2},${y2 - bend} ${x2},${y2}`;
    }
    const x1 = src.x + CW / 2,  y1 = src.y;
    const x2 = tgt.x - CW / 2,  y2 = tgt.y;
    const bend = Math.abs(x2 - x1) * 0.44;
    return `M${x1},${y1} C${x1 + bend},${y1} ${x2 - bend},${y2} ${x2},${y2}`;
}

// ── SVG initialisation ─────────────────────────────────────────────────────────
function initGraph() {
    const el = document.getElementById('topology-canvas');
    canvasW = el.clientWidth  || el.getBoundingClientRect().width  || 1200;
    canvasH = el.clientHeight || el.getBoundingClientRect().height || 700;

    svg = d3.select('#topology-canvas').append('svg')
        .attr('width', '100%').attr('height', canvasH);

    _defs = svg.append('defs');

    // Card drop shadow
    const sh = _defs.append('filter').attr('id', 'card-shadow')
        .attr('x', '-25%').attr('y', '-45%').attr('width', '150%').attr('height', '190%');
    sh.append('feDropShadow').attr('dx', 0).attr('dy', 6).attr('stdDeviation', 14)
        .attr('flood-color', '#000').attr('flood-opacity', parseFloat(tv('--topo-shadow-opacity')) || 0.70);

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
        .attr('fill', 'none').attr('stroke', tv('--topo-grid-stroke')).attr('stroke-width', 1);
    // Base canvas fill (themed) + grid overlay
    svg.append('rect').attr('width', '100%').attr('height', '100%').attr('fill', tv('--topo-canvas-bg'));
    svg.append('rect').attr('width', '100%').attr('height', '100%').attr('fill', 'url(#bg-grid)');

    // Radial vignette with subtle blue center warmth
    const vig = _defs.append('radialGradient').attr('id', 'bg-vig')
        .attr('cx', '50%').attr('cy', '50%').attr('r', '68%');
    vig.append('stop').attr('offset', '0%').attr('stop-color', 'rgba(59,130,246,0.022)');
    vig.append('stop').attr('offset', '100%').attr('stop-color', tv('--topo-vig-edge'));
    svg.append('rect').attr('width', '100%').attr('height', '100%').attr('fill', 'url(#bg-vig)');

    linkLayer = svg.append('g').attr('class', 'links');
    nodeLayer = svg.append('g').attr('class', 'nodes');

    // Click on SVG background closes panel
    svg.on('click', () => closePanel());
}

// ── Main render ───────────────────────────────────────────────────────────────
function renderGraph(data) {
    _lastData = data;

    // Inject placeholder nodes
    const addAgent = { id: 'add-agent', type: 'add-agent', name: 'Add Agent' };
    const addRoute = { id: 'add-route', type: 'add-route', name: 'Add Route' };

    const nodes = [...data.nodes, addAgent, addRoute];
    const links = data.links;

    // Store for panel usage
    _lastNodes = nodes;
    _lastLinks = links;

    if (!data.nodes.length) return;

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

    // ── Column / row labels ────────────────────────────────────────────────────
    const seen = new Set(data.nodes.map(n => n.type));
    seen.add('agent'); seen.add('route'); // always show
    if (isPortrait()) {
        // Row labels: left-aligned, above each row
        Object.entries(COL_LABEL).forEach(([type, label]) => {
            if (!seen.has(type)) return;
            svg.append('text').attr('class', 'col-label')
                .attr('x', 12).attr('y', ROW_Y[type] * canvasH - CH / 2 - 8)
                .attr('text-anchor', 'start')
                .attr('font-size', '7.5px').attr('font-weight', '700').attr('letter-spacing', '.20em')
                .attr('fill', tv('--topo-col-label')).attr('font-family', 'Inter, system-ui, sans-serif')
                .text(label);
        });
        // Horizontal row dividers
        [(ROW_Y.server + ROW_Y.agent) / 2 * canvasH, (ROW_Y.agent + ROW_Y.route) / 2 * canvasH]
            .forEach(y => {
                svg.append('line').attr('class', 'col-div')
                    .attr('x1', 24).attr('y1', y).attr('x2', canvasW - 24).attr('y2', y)
                    .attr('stroke', tv('--topo-col-div')).attr('stroke-width', 1)
                    .attr('stroke-dasharray', '3,9');
            });
    } else {
        // Vertical column labels (landscape)
        Object.entries(COL_LABEL).forEach(([type, label]) => {
            if (!seen.has(type)) return;
            svg.append('text').attr('class', 'col-label')
                .attr('x', COL_X[type] * canvasW).attr('y', 24)
                .attr('text-anchor', 'middle')
                .attr('font-size', '7.5px').attr('font-weight', '700').attr('letter-spacing', '.20em')
                .attr('fill', tv('--topo-col-label')).attr('font-family', 'Inter, system-ui, sans-serif')
                .text(label);
        });
        // Vertical column dividers
        [(COL_X.server + COL_X.agent) / 2 * canvasW, (COL_X.agent + COL_X.route) / 2 * canvasW]
            .forEach(x => {
                svg.append('line').attr('class', 'col-div')
                    .attr('x1', x).attr('y1', 40).attr('x2', x).attr('y2', canvasH - 24)
                    .attr('stroke', tv('--topo-col-div')).attr('stroke-width', 1)
                    .attr('stroke-dasharray', '3,9');
            });
    }

    // ── Edges ──────────────────────────────────────────────────────────────────
    edges.forEach((e, i) => {
        const isTunnel = e.type === 'tunnel';
        const sc = palette(e.src).accent;
        const tc = palette(e.tgt).accent;
        const gid = `eg${i}`;

        // Per-edge gradient — coordinates differ by orientation
        const [gx1, gy1, gx2, gy2] = isPortrait()
            ? [e.src.x,        e.src.y + CH / 2, e.tgt.x,        e.tgt.y - CH / 2]
            : [e.src.x + CW/2, e.src.y,           e.tgt.x - CW/2, e.tgt.y];
        _defs.append('linearGradient').attr('class', 'dyn').attr('id', gid)
            .attr('gradientUnits', 'userSpaceOnUse')
            .attr('x1', gx1).attr('y1', gy1)
            .attr('x2', gx2).attr('y2', gy2)
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
        if (!d.x || !d.y) return; // skip unpositioned

        const isPlaceholder = d.type === 'add-agent' || d.type === 'add-route';
        const col = palette(d);
        const g = nodeLayer.append('g').attr('class', 'node' + (isPlaceholder ? ' node-placeholder' : ''))
            .attr('transform', `translate(${d.x - CW / 2},${d.y - CH / 2})`)
            .style('opacity', 0)
            .style('cursor', d.type === 'server' ? 'default' : 'pointer');

        g.transition().duration(420).delay(i * 40).style('opacity', 1);

        if (isPlaceholder) {
            // Dashed placeholder card
            g.append('rect').attr('width', CW).attr('height', CH).attr('rx', CR)
                .attr('fill', tv('--topo-ph-fill'))
                .attr('stroke', tv('--topo-ph-stroke'))
                .attr('stroke-width', 1.2)
                .attr('stroke-dasharray', '6,4');

            // "+" icon in center
            const cx = CW / 2, cy = CH / 2;
            const ps = 9;
            g.append('line').attr('x1', cx).attr('y1', cy - ps).attr('x2', cx).attr('y2', cy + ps)
                .attr('stroke', tv('--topo-ph-icon')).attr('stroke-width', 1.8).attr('stroke-linecap', 'round');
            g.append('line').attr('x1', cx - ps).attr('y1', cy).attr('x2', cx + ps).attr('y2', cy)
                .attr('stroke', tv('--topo-ph-icon')).attr('stroke-width', 1.8).attr('stroke-linecap', 'round');

            // Label below the "+"
            g.append('text')
                .attr('x', CW / 2).attr('y', CH - 10).attr('text-anchor', 'middle')
                .attr('font-size', '8px').attr('font-weight', '600').attr('letter-spacing', '.10em')
                .attr('font-family', 'Inter, system-ui, sans-serif')
                .attr('fill', tv('--topo-ph-label'))
                .text(d.name.toUpperCase());

            // Hover effect
            g.on('mouseenter', function() {
                d3.select(this).select('rect')
                    .attr('fill', tv('--topo-ph-hover-fill'))
                    .attr('stroke', tv('--topo-ph-hover-stroke'));
                d3.select(this).selectAll('line')
                    .attr('stroke', tv('--topo-ph-hover-icon'));
                d3.select(this).select('text')
                    .attr('fill', tv('--topo-ph-hover-label'));
            }).on('mouseleave', function() {
                d3.select(this).select('rect')
                    .attr('fill', tv('--topo-ph-fill'))
                    .attr('stroke', tv('--topo-ph-stroke'));
                d3.select(this).selectAll('line')
                    .attr('stroke', tv('--topo-ph-icon'));
                d3.select(this).select('text')
                    .attr('fill', tv('--topo-ph-label'));
            });

        } else {
            // ① Ambient glow blob behind card
            g.append('ellipse')
                .attr('cx', CW / 2).attr('cy', CH / 2)
                .attr('rx', CW * 0.52).attr('ry', CH * 0.65)
                .attr('fill', col.glow).attr('opacity', parseFloat(tv('--topo-amb-glow-opacity')) || 0.30)
                .attr('filter', 'url(#amb-glow)');

            // ② Card dark background
            g.append('rect').attr('width', CW).attr('height', CH).attr('rx', CR)
                .attr('fill', tv('--topo-card-bg')).attr('filter', 'url(#card-shadow)');

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
                .attr('fill', active ? col.accent : tv('--topo-inactive-dot')).attr('opacity', 0.95);
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
                .attr('font-family', 'Inter, system-ui, sans-serif').attr('fill', tv('--topo-node-text'))
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
                .attr('fill', tv('--topo-type-badge'))
                .text(d.type.toUpperCase());

            // Hover highlight for clickable nodes
            if (d.type !== 'server') {
                g.on('mouseenter', function() {
                    d3.select(this).select('rect:nth-child(2)')
                        .transition().duration(120).attr('fill', tv('--topo-card-hover-bg'));
                    d3.select(this).select('rect:nth-child(5)')
                        .transition().duration(120).attr('stroke-opacity', 0.82);
                }).on('mouseleave', function() {
                    d3.select(this).select('rect:nth-child(2)')
                        .transition().duration(180).attr('fill', tv('--topo-card-bg'));
                    d3.select(this).select('rect:nth-child(5)')
                        .transition().duration(180).attr('stroke-opacity', 0.52);
                });
            }
        }

        // Click handler (all nodes except server)
        if (d.type !== 'server') {
            g.on('click', function(event) {
                event.stopPropagation();
                openPanel(d, _lastNodes, _lastLinks);
            });
        }
    });
}

// ── Panel management ───────────────────────────────────────────────────────────
function openPanel(d, allNodes, allLinks) {
    const panel     = document.getElementById('px-panel');
    const body      = document.getElementById('px-panel-body');
    const title     = document.getElementById('px-panel-title');
    const csrf      = document.querySelector('meta[name="_csrf"]')?.getAttribute('content') || '';
    const csrfParam = document.querySelector('meta[name="_csrf_param"]')?.getAttribute('content') || '_csrf';

    if (d.type === 'add-agent') {
        // Use the Bootstrap modal for creating agents
        const modal = bootstrap.Modal.getOrCreateInstance(document.getElementById('newAgentModal'));
        modal.show();
        return;
    }

    if (d.type === 'add-route') {
        title.textContent = 'New Route';
        body.innerHTML = buildAddRoutePanel();
    } else if (d.type === 'agent') {
        title.textContent = d.name;
        const agentRoutes = allNodes.filter(n => {
            if (n.type !== 'route') return false;
            return allLinks.some(l => {
                const src = l.source.id || l.source;
                const tgt = l.target.id || l.target;
                return src === d.id && tgt === n.id;
            });
        });
        body.innerHTML = buildAgentPanel(d, agentRoutes, csrf, csrfParam);
    } else if (d.type === 'route') {
        title.textContent = d.name;
        const link = allLinks.find(l => (l.target.id || l.target) === d.id);
        const agentNode = link
            ? allNodes.find(n => n.id === (link.source.id || link.source) && n.type === 'agent') || null
            : null;
        body.innerHTML = buildRoutePanel(d, agentNode, csrf, csrfParam);
    }

    panel.classList.add('open');
    document.getElementById('px-panel-backdrop').classList.add('open');
}

function closePanel() {
    document.getElementById('px-panel')?.classList.remove('open');
    document.getElementById('px-panel-backdrop')?.classList.remove('open');
}

// ── Panel HTML builders ────────────────────────────────────────────────────────
function buildAgentPanel(d, routes, csrf, csrfParam) {
    const statusColor = d.connected ? '#34d399' : '#64748b';
    const statusText  = d.connected ? 'Online' : 'Offline';
    const routesHtml  = routes.length
        ? routes.map(r => `
            <div class="px-panel-item" onclick="window.location='/admin/routes/${r.id}'" style="cursor:pointer;">
                <span class="px-panel-item-dot" style="background:${r.enabled !== false ? '#fbbf24' : '#64748b'};"></span>
                <span>${escHtml(r.name)}</span>
                <span class="ms-auto" style="color:rgba(255,255,255,0.30); font-size:.78rem;">${escHtml(r.target || '')}</span>
            </div>`).join('')
        : '<p class="mb-0" style="color:rgba(255,255,255,0.3); font-size:.83rem;">No routes assigned.</p>';

    return `
        <div class="px-panel-section">
            <div class="px-panel-status-row">
                <span class="px-panel-status-dot" style="background:${statusColor};
                    box-shadow:0 0 0 4px ${statusColor}22;"></span>
                <span class="px-panel-status-text">${statusText}</span>
                <span class="px-panel-badge ms-2">${escHtml(d.status || '')}</span>
            </div>
            <div class="px-panel-meta mt-2">
                <span style="color:rgba(255,255,255,0.3);">ID</span>&nbsp;
                <code style="font-size:.72rem; color:rgba(255,255,255,0.55);">${escHtml(d.id)}</code>
            </div>
        </div>

        <div class="px-panel-section">
            <div class="px-panel-section-label">Assigned Routes</div>
            ${routesHtml}
        </div>

        <div class="px-panel-section">
            <div class="px-panel-section-label">Actions</div>
            <div class="d-flex flex-column gap-2 mt-2">
                <a href="/admin/agents/${d.id}" class="px-panel-action">
                    <i class="bi bi-info-circle"></i>View Details
                </a>
                <a href="/admin/routes/new" class="px-panel-action">
                    <i class="bi bi-plus-lg"></i>Add Route to Agent
                </a>
                <form method="post" action="/admin/agents/${d.id}/token">
                    <input type="hidden" name="${csrfParam}" value="${csrf}"/>
                    <button type="submit" class="px-panel-action px-panel-action-warn w-100 text-start border-0 bg-transparent">
                        <i class="bi bi-key"></i>Generate New Token
                    </button>
                </form>
                <form method="post" action="/admin/agents/${d.id}/delete"
                      onsubmit="return confirm('Delete agent &quot;${escHtml(d.name)}&quot;?');">
                    <input type="hidden" name="${csrfParam}" value="${csrf}"/>
                    <button type="submit" class="px-panel-action px-panel-action-danger w-100 text-start border-0 bg-transparent">
                        <i class="bi bi-trash"></i>Delete Agent
                    </button>
                </form>
            </div>
        </div>`;
}

function buildRoutePanel(d, agentNode, csrf, csrfParam) {
    const enabledColor = d.enabled !== false ? '#fbbf24' : '#64748b';
    const enabledText  = d.enabled !== false ? 'Enabled' : 'Disabled';
    const agentHtml    = agentNode
        ? `<div class="px-panel-item"
               onclick="openPanel(_lastNodes.find(n=>n.id==='${agentNode.id}'),_lastNodes,_lastLinks)"
               style="cursor:pointer;">
               <span class="px-panel-item-dot"
                     style="background:${agentNode.connected ? '#34d399' : '#64748b'};"></span>
               <span>${escHtml(agentNode.name)}</span>
           </div>`
        : '<p class="mb-0" style="color:rgba(255,255,255,0.3); font-size:.83rem;">No agent assigned.</p>';

    return `
        <div class="px-panel-section">
            <div class="px-panel-status-row">
                <span class="px-panel-status-dot" style="background:${enabledColor};
                    box-shadow:0 0 0 4px ${enabledColor}22;"></span>
                <span class="px-panel-status-text">${enabledText}</span>
            </div>
            <div class="px-panel-meta mt-2">
                <i class="bi bi-arrow-right-circle me-1" style="color:rgba(255,255,255,0.3);"></i>
                <code style="font-size:.8rem; color:rgba(255,255,255,0.6);">${escHtml(d.target || '—')}</code>
            </div>
            <div class="px-panel-meta mt-1">
                <span style="color:rgba(255,255,255,0.3);">ID</span>&nbsp;
                <code style="font-size:.72rem; color:rgba(255,255,255,0.55);">${escHtml(d.id)}</code>
            </div>
        </div>

        <div class="px-panel-section">
            <div class="px-panel-section-label">Agent</div>
            ${agentHtml}
        </div>

        <div class="px-panel-section">
            <div class="px-panel-section-label">Actions</div>
            <div class="d-flex flex-column gap-2 mt-2">
                <a href="/admin/routes/${d.id}" class="px-panel-action">
                    <i class="bi bi-list-ul"></i>View Details &amp; Live Log
                </a>
                <a href="/admin/routes" class="px-panel-action">
                    <i class="bi bi-pencil"></i>Edit on Routes Page
                </a>
                <form method="post" action="/admin/routes/${d.id}/delete"
                      onsubmit="return confirm('Delete route &quot;${escHtml(d.name)}&quot;?');">
                    <input type="hidden" name="${csrfParam}" value="${csrf}"/>
                    <button type="submit" class="px-panel-action px-panel-action-danger w-100 text-start border-0 bg-transparent">
                        <i class="bi bi-trash"></i>Delete Route
                    </button>
                </form>
            </div>
        </div>`;
}

function buildAddRoutePanel() {
    return `
        <div class="px-panel-section" style="padding-top:2.5rem; text-align:center;">
            <div style="font-size:3rem; color:rgba(251,191,36,0.45); margin-bottom:1rem;">
                <i class="bi bi-signpost-split"></i>
            </div>
            <p style="color:rgba(255,255,255,0.45); font-size:.88rem; line-height:1.6; margin-bottom:1.75rem;">
                Create a new route to expose a LAN service through an agent tunnel.
            </p>
            <a href="/admin/routes/new" class="px-panel-action" style="display:block; text-align:center; justify-content:center;">
                <i class="bi bi-plus-lg"></i>Create New Route
            </a>
        </div>`;
}

function escHtml(s) {
    return String(s)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
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

// Re-initialise the SVG and re-render with cached data (used when theme changes)
function reinitAndRender() {
    const el = document.getElementById('topology-canvas');
    if (!el) return;
    // Clear existing SVG
    while (el.firstChild) el.removeChild(el.firstChild);
    svg = null; linkLayer = null; nodeLayer = null; _defs = null;
    initGraph();
    if (_lastData.nodes.length) renderGraph(_lastData);
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

// Resize canvas when window changes
window.addEventListener('resize', () => {
    const el = document.getElementById('topology-canvas');
    if (!el || !svg) return;
    canvasW = el.clientWidth;
    canvasH = el.clientHeight;
    svg.attr('height', canvasH);
    if (_lastData.nodes.length) renderGraph(_lastData);
});

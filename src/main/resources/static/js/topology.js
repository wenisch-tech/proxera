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

function isLightMode() {
    return document.documentElement.getAttribute('data-bs-theme') === 'light';
}

// ── Column fractions of canvas width ──────────────────────────────────────────
const COL_X     = { ingress: 0.07, server: 0.27, agent: 0.52, route: 0.80 };
const ROW_Y     = { ingress: 0.09, server: 0.30, agent: 0.57, route: 0.82 };  // portrait fractions of canvasH
const COL_LABEL = { ingress: 'INGRESS', server: 'PROXY SERVER', agent: 'AGENTS', route: 'ROUTES' };
const MIN_GAP   = 118;   // vertical pitch between nodes in landscape (px)
const MIN_GAP_H = 188;   // horizontal pitch between nodes in portrait (px)

function isPortrait() { return canvasW < 700; }

let svg, _defs, linkLayer, nodeLayer, canvasW, canvasH;
let _zoom, _zoomG, _zoomInitialized = false;

// Keep the last topology data for panel usage and theme re-rendering
let _lastNodes = [];
let _lastLinks = [];
let _lastData  = { nodes: [], links: [] };

// ── Color palette per node state ───────────────────────────────────────────────
function palette(d) {
    const lm = isLightMode();
    if (d.type === 'server')
        return { accent: '#818cf8', glow: '#4338ca', border: 'rgba(129,140,248,0.55)', muted: lm ? '#4338ca' : '#c7d2fe' };
    if (d.type === 'agent' && d.connected)
        return { accent: '#34d399', glow: '#059669', border: 'rgba(52,211,153,0.50)', muted: lm ? '#047857' : '#a7f3d0' };
    if (d.type === 'agent')
        return { accent: '#64748b', glow: '#1e293b', border: 'rgba(100,116,139,0.38)', muted: lm ? '#475569' : '#94a3b8' };
    if (d.type === 'add-agent' || d.type === 'add-route' || d.type === 'add-ingress')
        return { accent: '#475569', glow: '#1e293b', border: 'rgba(71,85,105,0.35)', muted: lm ? '#475569' : '#64748b' };
    if (d.type === 'ingress')
        return { accent: '#22d3ee', glow: '#0891b2', border: 'rgba(34,211,238,0.50)', muted: lm ? '#0e7490' : '#a5f3fc' };
    if (d.type === 'ingress-unavailable')
        return { accent: '#475569', glow: '#1e293b', border: 'rgba(71,85,105,0.30)', muted: lm ? '#64748b' : '#64748b' };
    if (d.enabled !== false)
        return { accent: '#fbbf24', glow: '#b45309', border: 'rgba(251,191,36,0.45)', muted: lm ? '#92400e' : '#fde68a' };
    return { accent: '#64748b', glow: '#1e293b', border: 'rgba(100,116,139,0.38)', muted: lm ? '#475569' : '#94a3b8' };
}

function metaLabel(d) {
    const trim = (s, n) => s.length > n ? s.slice(0, n - 1) + '\u2026' : s;
    if (d.type === 'server')             return 'Reverse Proxy  \u00b7  Active';
    if (d.type === 'add-agent')          return 'Click to create';
    if (d.type === 'add-route')          return 'Click to create';
    if (d.type === 'add-ingress')        return 'Click to create';
    if (d.type === 'ingress')            return d.host ? trim(d.host, 20) : '\u25cf Active';
    if (d.type === 'ingress-unavailable') return 'Not available';
    if (d.type === 'agent')             return d.connected ? '\u25cf Online' : '\u25cb Offline';
    if (d.target)                        return '\u2192 ' + trim(String(d.target), 18);
    return d.enabled !== false ? '\u25cf Enabled' : '\u25cb Disabled';
}

// ── Icon drawing, centered at (0,0) ───────────────────────────────────────────
function drawIcon(g, d) {
    const c = palette(d).accent;

    if (d.type === 'add-agent' || d.type === 'add-route' || d.type === 'add-ingress') {
        // Big "+" icon
        g.append('line').attr('x1', 0).attr('y1', -9).attr('x2', 0).attr('y2', 9)
            .attr('stroke', c).attr('stroke-width', 2.2).attr('stroke-linecap', 'round');
        g.append('line').attr('x1', -9).attr('y1', 0).attr('x2', 9).attr('y2', 0)
            .attr('stroke', c).attr('stroke-width', 2.2).attr('stroke-linecap', 'round');
        return;
    }

    if (d.type === 'ingress' || d.type === 'ingress-unavailable') {
        const op = d.type === 'ingress-unavailable' ? 0.45 : 1;
        // Cloud outline
        g.append('path')
            .attr('d', 'M-10,3 A6,6 0 0,1 -4,-3 A5,5 0 0,1 5,-6 A5,5 0 0,1 10,0 A4,4 0 0,1 10,8 L-10,8 A4,4 0 0,1 -10,3 Z')
            .attr('fill', 'none').attr('stroke', c).attr('stroke-width', 1.5)
            .attr('stroke-linejoin', 'round').attr('opacity', op);
        if (d.type === 'ingress') {
            // Arrow pointing down (traffic coming in)
            g.append('line').attr('x1', 0).attr('y1', -10).attr('x2', 0).attr('y2', -6)
                .attr('stroke', c).attr('stroke-width', 1.5).attr('stroke-linecap', 'round');
            g.append('path').attr('d', 'M-3,-8 L0,-5 L3,-8')
                .attr('fill', 'none').attr('stroke', c).attr('stroke-width', 1.5)
                .attr('stroke-linejoin', 'round').attr('stroke-linecap', 'round');
        } else {
            // X mark for unavailable
            g.append('line').attr('x1', -3).attr('y1', -10).attr('x2', 3).attr('y2', -5)
                .attr('stroke', c).attr('stroke-width', 1.5).attr('stroke-linecap', 'round').attr('opacity', op);
            g.append('line').attr('x1', 3).attr('y1', -10).attr('x2', -3).attr('y2', -5)
                .attr('stroke', c).attr('stroke-width', 1.5).attr('stroke-linecap', 'round').attr('opacity', op);
        }
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
    const groups = {
        server: [], agent: [], route: [],
        'add-agent': [], 'add-route': [],
        ingress: [], 'ingress-unavailable': [], 'add-ingress': []
    };
    nodes.forEach(n => { if (groups[n.type]) groups[n.type].push(n); });

    if (isPortrait()) {
        // Portrait: rows (top → bottom), nodes spread horizontally within each row
        ['ingress', 'ingress-unavailable', 'server', 'agent', 'route'].forEach(type => {
            const rowKey = (type === 'ingress-unavailable') ? 'ingress' : type;
            const group = groups[type];
            if (!group.length) return;
            const cy   = ROW_Y[rowKey] * canvasH;
            const span = (group.length - 1) * MIN_GAP_H;
            const left = canvasW / 2 - span / 2;
            group.forEach((n, i) => { n.x = left + i * MIN_GAP_H; n.y = cy; });
        });
        // Placeholder nodes to the right of the last real node in their row
        ['add-agent', 'add-route', 'add-ingress'].forEach(type => {
            const rowType = type === 'add-agent' ? 'agent' : type === 'add-route' ? 'route' : 'ingress';
            const real = groups[rowType];
            const placeholder = groups[type];
            if (!placeholder.length) return;
            const cy    = ROW_Y[rowType] * canvasH;
            const lastX = real.length ? real[real.length - 1].x + MIN_GAP_H : canvasW / 2;
            placeholder.forEach((n, i) => { n.x = lastX + i * MIN_GAP_H; n.y = cy; });
        });
        return;
    }

    // Landscape: columns (left → right)
    // Ingress column (real ingresses + unavailable node share the same x)
    [...groups.ingress, ...groups['ingress-unavailable']].forEach((n, i, arr) => {
        const cx   = COL_X.ingress * canvasW;
        const span = (arr.length - 1) * MIN_GAP;
        const top  = canvasH / 2 - span / 2;
        n.x = cx;
        n.y = top + i * MIN_GAP;
    });

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

    // add-ingress placeholder below last ingress/unavailable node
    {
        const real = [...groups.ingress, ...groups['ingress-unavailable']];
        const placeholder = groups['add-ingress'];
        if (placeholder.length) {
            const cx    = COL_X.ingress * canvasW;
            const lastY = real.length ? real[real.length - 1].y + MIN_GAP : canvasH / 2;
            placeholder.forEach((n, i) => { n.x = cx; n.y = lastY + i * MIN_GAP; });
        }
    }
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

    // Zoomable/pannable container — everything rendered inside here
    _zoomG = svg.append('g').attr('class', 'zoom-root');

    _zoom = d3.zoom()
        .scaleExtent([0.12, 4])
        .on('zoom', (e) => _zoomG.attr('transform', e.transform));
    svg.call(_zoom).on('dblclick.zoom', null);

    linkLayer = _zoomG.append('g').attr('class', 'links');
    nodeLayer  = _zoomG.append('g').attr('class', 'nodes');

    // Click on SVG background closes panel
    svg.on('click', () => closePanel());
}

// ── Main render ───────────────────────────────────────────────────────────────
function renderGraph(data) {
    _lastData = data;

    // Inject placeholder nodes
    const addAgent = { id: 'add-agent', type: 'add-agent', name: 'Add Agent' };
    const addRoute = { id: 'add-route', type: 'add-route', name: 'Add Route' };
    const addIngress = { id: 'add-ingress', type: 'add-ingress', name: 'Add Ingress' };

    const ingressPlaceholders = data.kubernetesAvailable === true ? [addIngress] : [];
    const nodes = [...data.nodes, addAgent, addRoute, ...ingressPlaceholders];
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
    _zoomG.selectAll('.col-label, .col-div').remove();

    // ── Column / row labels ────────────────────────────────────────────────────
    const seen = new Set(data.nodes.map(n => n.type));
    seen.add('agent'); seen.add('route'); seen.add('ingress'); // always show
    if (isPortrait()) {
        // Row labels: left-aligned, above each row
        Object.entries(COL_LABEL).forEach(([type, label]) => {
            if (!seen.has(type)) return;
            _zoomG.append('text').attr('class', 'col-label')
                .attr('x', 12).attr('y', ROW_Y[type] * canvasH - CH / 2 - 8)
                .attr('text-anchor', 'start')
                .attr('font-size', '7.5px').attr('font-weight', '700').attr('letter-spacing', '.20em')
                .attr('fill', tv('--topo-col-label')).attr('font-family', 'Inter, system-ui, sans-serif')
                .text(label);
        });
        // Horizontal row dividers
        [(ROW_Y.ingress + ROW_Y.server) / 2 * canvasH, (ROW_Y.server + ROW_Y.agent) / 2 * canvasH, (ROW_Y.agent + ROW_Y.route) / 2 * canvasH]
            .forEach(y => {
                _zoomG.append('line').attr('class', 'col-div')
                    .attr('x1', 24).attr('y1', y).attr('x2', canvasW - 24).attr('y2', y)
                    .attr('stroke', tv('--topo-col-div')).attr('stroke-width', 1)
                    .attr('stroke-dasharray', '3,9');
            });
    } else {
        // Vertical column labels (landscape)
        Object.entries(COL_LABEL).forEach(([type, label]) => {
            if (!seen.has(type)) return;
            _zoomG.append('text').attr('class', 'col-label')
                .attr('x', COL_X[type] * canvasW).attr('y', 24)
                .attr('text-anchor', 'middle')
                .attr('font-size', '7.5px').attr('font-weight', '700').attr('letter-spacing', '.20em')
                .attr('fill', tv('--topo-col-label')).attr('font-family', 'Inter, system-ui, sans-serif')
                .text(label);
        });
        // Vertical column dividers
        [(COL_X.ingress + COL_X.server) / 2 * canvasW, (COL_X.server + COL_X.agent) / 2 * canvasW, (COL_X.agent + COL_X.route) / 2 * canvasW]
            .forEach(x => {
                _zoomG.append('line').attr('class', 'col-div')
                    .attr('x1', x).attr('y1', 40).attr('x2', x).attr('y2', canvasH - 24)
                    .attr('stroke', tv('--topo-col-div')).attr('stroke-width', 1)
                    .attr('stroke-dasharray', '3,9');
            });
    }

    // ── Edges ──────────────────────────────────────────────────────────────────
    edges.forEach((e, i) => {
        const isTunnel = e.type === 'tunnel';
        const isIngress = e.type === 'ingress';
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
            .attr('stroke-dasharray', (isTunnel || isIngress) ? null : '6,5')
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

        const isPlaceholder = d.type === 'add-agent' || d.type === 'add-route' || d.type === 'add-ingress';
        const col = palette(d);
        const g = nodeLayer.append('g').attr('class', 'node' + (isPlaceholder ? ' node-placeholder' : ''))
            .attr('transform', `translate(${d.x - CW / 2},${d.y - CH / 2})`)
            .style('opacity', 0)
            .style('cursor', 'pointer');

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

            // ③ Icon zone: solid fill in light mode, left-to-right gradient in dark mode
            const clipId = `clip${i}`;
            _defs.append('clipPath').attr('class', 'dyn').attr('id', clipId)
                .append('rect').attr('width', CW).attr('height', CH).attr('rx', CR);
            if (isLightMode()) {
                g.append('rect').attr('width', IW).attr('height', CH)
                    .attr('fill', col.accent).attr('fill-opacity', 0.18)
                    .attr('clip-path', `url(#${clipId})`);
            } else {
                const izgId = `izg${i}`;
                _defs.append('linearGradient').attr('class', 'dyn').attr('id', izgId)
                    .attr('x1', '0%').attr('x2', '100%').attr('y1', '0%').attr('y2', '0%')
                    .call(gr => {
                        gr.append('stop').attr('offset', '0%').attr('stop-color', col.glow).attr('stop-opacity', 0.32);
                        gr.append('stop').attr('offset', '100%').attr('stop-color', col.glow).attr('stop-opacity', 0);
                    });
                g.append('rect').attr('width', IW).attr('height', CH)
                    .attr('fill', `url(#${izgId})`).attr('clip-path', `url(#${clipId})`);
            }

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

            // ⑪ IP labels floating below the card
            //    external / source IP → left edge  (◂ prefix)
            //    internal IP          → right edge (▸ suffix)
            {
                const extIp = d.externalIp || d.remoteIp;
                const intIp = d.internalIp;
                if (extIp || intIp) {
                    const tickY2 = CH + 5;
                    const ipY    = CH + 13;
                    const ipFont = '"Courier New", Courier, monospace';
                    if (extIp) {
                        g.append('line')
                            .attr('x1', 16).attr('y1', CH).attr('x2', 16).attr('y2', tickY2)
                            .attr('stroke', col.muted).attr('stroke-width', 0.7).attr('opacity', 0.42);
                        g.append('text')
                            .attr('x', 4).attr('y', ipY)
                            .attr('font-size', '7px').attr('font-family', ipFont)
                            .attr('fill', col.muted).attr('opacity', 0.78)
                            .attr('text-anchor', 'start')
                            .text('\u25c2 ' + extIp);
                    }
                    if (intIp) {
                        g.append('line')
                            .attr('x1', CW - 16).attr('y1', CH).attr('x2', CW - 16).attr('y2', tickY2)
                            .attr('stroke', col.muted).attr('stroke-width', 0.7).attr('opacity', 0.42);
                        g.append('text')
                            .attr('x', CW - 4).attr('y', ipY)
                            .attr('font-size', '7px').attr('font-family', ipFont)
                            .attr('fill', col.muted).attr('opacity', 0.78)
                            .attr('text-anchor', 'end')
                            .text(intIp + ' \u25b8');
                    }
                }
            }

            // Hover highlight for clickable nodes
            if (true) {
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

        // Click handler (all nodes)
        g.on('click', function(event) {
            event.stopPropagation();
            openPanel(d, _lastNodes, _lastLinks);
        });
    });

    // Auto-fit all nodes into view on the very first render
    if (!_zoomInitialized) {
        _zoomInitialized = true;
        fitToView(false);
    }
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

    if (d.type === 'add-ingress') {
        const modal = bootstrap.Modal.getOrCreateInstance(document.getElementById('newIngressModal'));
        modal.show();
        return;
    }

    if (d.type === 'ingress-unavailable') {
        title.textContent = 'Ingress';
        body.innerHTML = buildIngressUnavailablePanel();
    } else if (d.type === 'add-route') {
        title.textContent = 'New Route';
        body.innerHTML = buildAddRoutePanel();
    } else if (d.type === 'server') {
        title.textContent = d.name;
        body.innerHTML = buildServerPanel(d);
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
    } else if (d.type === 'ingress') {
        title.textContent = d.name;
        body.innerHTML = buildIngressPanel(d, csrf, csrfParam);
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
                <span class="ms-auto" style="color:var(--px-muted); font-size:.78rem;">${escHtml(r.target || '')}</span>
            </div>`).join('')
        : '<p class="mb-0" style="color:var(--px-muted); font-size:.83rem;">No routes assigned.</p>';

    return `
        <div class="px-panel-section">
            <div class="px-panel-status-row">
                <span class="px-panel-status-dot" style="background:${statusColor};
                    box-shadow:0 0 0 4px ${statusColor}22;"></span>
                <span class="px-panel-status-text">${statusText}</span>
                <span class="px-panel-badge ms-2">${escHtml(d.status || '')}</span>
            </div>
            <div class="px-panel-meta mt-2">
                <span style="color:var(--px-muted);">ID</span>&nbsp;
                <code style="font-size:.72rem; color:var(--px-text);">${escHtml(d.id)}</code>
            </div>
            ${d.remoteIp ? `<div class="px-panel-meta mt-1">
                <span style="color:var(--px-muted);">Source IP</span>&nbsp;
                <code style="font-size:.72rem; color:var(--px-text);">${escHtml(d.remoteIp)}</code>
            </div>` : ''}
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

function buildServerPanel(d) {
    const networkRows = [];
    if (d.internalIp) {
        networkRows.push(`<div class="px-panel-meta">
            <span style="color:var(--px-muted);">Internal IP</span>&nbsp;
            <code style="font-size:.72rem; color:var(--px-text);">${escHtml(d.internalIp)}</code>
        </div>`);
    }
    if (d.externalIp) {
        networkRows.push(`<div class="px-panel-meta mt-1">
            <span style="color:var(--px-muted);">External IP</span>&nbsp;
            <code style="font-size:.72rem; color:var(--px-text);">${escHtml(d.externalIp)}</code>
        </div>`);
    }
    if (!networkRows.length) {
        networkRows.push(`<p class="mb-0" style="color:var(--px-muted); font-size:.83rem;">No IP information available.</p>`);
    }

    return `
        <div class="px-panel-section">
            <div class="px-panel-status-row">
                <span class="px-panel-status-dot" style="background:#34d399;
                    box-shadow:0 0 0 4px #34d39922;"></span>
                <span class="px-panel-status-text">Active</span>
            </div>
        </div>

        <div class="px-panel-section">
            <div class="px-panel-section-label">Network</div>
            ${networkRows.join('')}
        </div>

        <div class="px-panel-section">
            <div class="px-panel-section-label">Actions</div>
            <div class="d-flex flex-column gap-2 mt-2">
                <a href="/admin/agents" class="px-panel-action">
                    <i class="bi bi-hdd-rack"></i>Manage Agents
                </a>
                <a href="/admin/routes" class="px-panel-action">
                    <i class="bi bi-signpost-split"></i>Manage Routes
                </a>
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
        : '<p class="mb-0" style="color:var(--px-muted); font-size:.83rem;">No agent assigned.</p>';

    return `
        <div class="px-panel-section">
            <div class="px-panel-status-row">
                <span class="px-panel-status-dot" style="background:${enabledColor};
                    box-shadow:0 0 0 4px ${enabledColor}22;"></span>
                <span class="px-panel-status-text">${enabledText}</span>
            </div>
            <div class="px-panel-meta mt-2">
                <i class="bi bi-arrow-right-circle me-1" style="color:var(--px-muted);"></i>
                <code style="font-size:.8rem; color:var(--px-text);">${escHtml(d.target || '—')}</code>
            </div>
            <div class="px-panel-meta mt-1">
                <span style="color:var(--px-muted);">ID</span>&nbsp;
                <code style="font-size:.72rem; color:var(--px-text);">${escHtml(d.id)}</code>
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
            <p style="color:var(--px-muted); font-size:.88rem; line-height:1.6; margin-bottom:1.75rem;">
                Create a new route to expose a LAN service through an agent tunnel.
            </p>
            <a href="/admin/routes/new" class="px-panel-action" style="display:block; text-align:center; justify-content:center;">
                <i class="bi bi-plus-lg"></i>Create New Route
            </a>
        </div>`;
}

function buildIngressUnavailablePanel() {
    return `
        <div class="px-panel-section" style="padding-top:2.5rem; text-align:center;">
            <div style="font-size:3rem; color:rgba(100,116,139,0.45); margin-bottom:1rem;">
                <i class="bi bi-cloud-slash"></i>
            </div>
            <p style="color:var(--px-muted); font-size:.88rem; line-height:1.6;">
                Ingress management is only available when Proxera is running inside a Kubernetes cluster.
            </p>
        </div>`;
}

function buildIngressPanel(d, csrf, csrfParam) {
    const annotationsHtml = Object.entries(d.annotations || {}).map(([k, v], idx) => `
        <div class="row g-1 mb-1 annotation-row" id="ig-ann-row-${idx}">
            <div class="col">
                <input type="text" class="form-control form-control-sm" name="annotationKeys"
                       value="${escHtml(k)}" placeholder="annotation key">
            </div>
            <div class="col">
                <input type="text" class="form-control form-control-sm" name="annotationValues"
                       value="${escHtml(v)}" placeholder="value">
            </div>
            <div class="col-auto">
                <button type="button" class="btn btn-sm btn-outline-secondary"
                        onclick="this.closest('.annotation-row').remove()">
                    <i class="bi bi-x"></i>
                </button>
            </div>
        </div>`).join('');

    return `
        <form method="POST" action="/admin/ingresses/${escHtml(d.name)}">
            <input type="hidden" name="${escHtml(csrfParam)}" value="${escHtml(csrf)}">
            <div class="px-panel-section">
                <div class="mb-3">
                    <label class="form-label form-label-sm">Class Name</label>
                    <input type="text" class="form-control form-control-sm" name="className"
                           value="${escHtml(d.className || '')}" placeholder="nginx">
                </div>
                <div class="mb-2">
                    <label class="form-label form-label-sm d-flex justify-content-between">
                        Annotations
                        <button type="button" class="btn btn-link btn-sm p-0"
                                onclick="addIngressAnnotationRow('ig-annotations')">
                            <i class="bi bi-plus-circle"></i> Add
                        </button>
                    </label>
                    <div id="ig-annotations">
                        ${annotationsHtml}
                    </div>
                </div>
                <div class="mb-3">
                    <label class="form-label form-label-sm">Host</label>
                    <input type="text" class="form-control form-control-sm" name="host"
                           value="${escHtml(d.host || '')}" placeholder="example.com">
                </div>
                <div class="row g-2 mb-3">
                    <div class="col">
                        <label class="form-label form-label-sm">Path</label>
                        <input type="text" class="form-control form-control-sm" name="path"
                               value="${escHtml(d.path || '/')}" placeholder="/">
                    </div>
                    <div class="col">
                        <label class="form-label form-label-sm">Path Type</label>
                        <select class="form-select form-select-sm" name="pathType">
                            <option value="ImplementationSpecific" ${d.pathType === 'ImplementationSpecific' ? 'selected' : ''}>ImplementationSpecific</option>
                            <option value="Prefix" ${d.pathType === 'Prefix' ? 'selected' : ''}>Prefix</option>
                            <option value="Exact" ${d.pathType === 'Exact' ? 'selected' : ''}>Exact</option>
                        </select>
                    </div>
                </div>
                <div class="mb-2">
                    <div class="form-check">
                        <input class="form-check-input" type="checkbox" name="tlsEnabled" value="true"
                               id="ig-tls-edit" ${d.tlsEnabled ? 'checked' : ''}
                               onchange="document.getElementById('ig-tls-secret-edit').classList.toggle('d-none', !this.checked)">
                        <label class="form-check-label" for="ig-tls-edit">Enable TLS</label>
                    </div>
                </div>
                <div class="mb-3 ${d.tlsEnabled ? '' : 'd-none'}" id="ig-tls-secret-edit">
                    <label class="form-label form-label-sm">TLS Secret Name</label>
                    <input type="text" class="form-control form-control-sm" name="tlsSecretName"
                           value="${escHtml(d.tlsSecretName || '')}" placeholder="my-tls-secret">
                </div>
                <div class="d-flex gap-2">
                    <button type="submit" class="px-panel-action flex-grow-1 justify-content-center">
                        <i class="bi bi-save"></i> Save
                    </button>
                </div>
            </div>
        </form>
        <form method="POST" action="/admin/ingresses/${escHtml(d.name)}/delete">
            <input type="hidden" name="${escHtml(csrfParam)}" value="${escHtml(csrf)}">
            <div class="px-panel-section" style="padding-top:0;">
                <button type="submit" class="px-panel-action px-panel-danger w-100 justify-content-center"
                        onclick="return confirm('Delete ingress ${escHtml(d.name)}?')">
                    <i class="bi bi-trash"></i> Delete Ingress
                </button>
            </div>
        </form>`;
}

function addIngressAnnotationRow(containerId) {
    const container = document.getElementById(containerId);
    if (!container) return;
    const idx = container.querySelectorAll('.annotation-row').length;
    const row = document.createElement('div');
    row.className = 'row g-1 mb-1 annotation-row';
    row.id = `ig-ann-row-${idx}`;
    row.innerHTML = `
        <div class="col">
            <input type="text" class="form-control form-control-sm" name="annotationKeys" placeholder="annotation key">
        </div>
        <div class="col">
            <input type="text" class="form-control form-control-sm" name="annotationValues" placeholder="value">
        </div>
        <div class="col-auto">
            <button type="button" class="btn btn-sm btn-outline-secondary"
                    onclick="this.closest('.annotation-row').remove()">
                <i class="bi bi-x"></i>
            </button>
        </div>`;
    container.appendChild(row);
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
    svg = null; linkLayer = null; nodeLayer = null; _defs = null; _zoomG = null; _zoomInitialized = false;
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

// ── Zoom controls ──────────────────────────────────────────────────────────────
function fitToView(animate) {
    const positioned = _lastNodes.filter(n => n.x && n.y);
    if (!positioned.length || !_zoom || !svg) return;
    const pad = 20;
    let x0 = Math.min(...positioned.map(n => n.x)) - CW / 2 - pad;
    let y0 = Math.min(...positioned.map(n => n.y)) - CH / 2 - pad;
    let x1 = Math.max(...positioned.map(n => n.x)) + CW / 2 + pad;
    let y1 = Math.max(...positioned.map(n => n.y)) + CH / 2 + pad + 20; // +20 for IP labels

    // Expand to include column/row labels which live outside the node bounding box
    if (isPortrait()) {
        // Row labels sit at x=12, y = ROW_Y[type]*canvasH - CH/2 - 8
        x0 = Math.min(x0, 0);
        y0 = Math.min(y0, ROW_Y.ingress * canvasH - CH / 2 - 20);
        y1 = Math.max(y1, ROW_Y.route * canvasH + CH / 2 + pad);
    } else {
        // Col labels sit at y=24; span from ingress col to route col
        y0 = Math.min(y0, 8);
        x0 = Math.min(x0, COL_X.ingress * canvasW - CW / 2 - pad);
        x1 = Math.max(x1, COL_X.route  * canvasW + CW / 2 + pad);
    }

    const bw = x1 - x0, bh = y1 - y0;
    const scale = Math.min(canvasW / bw, canvasH / bh, 2);
    const tx = (canvasW - scale * (x0 + x1)) / 2;
    const ty = (canvasH - scale * (y0 + y1)) / 2;
    const transform = d3.zoomIdentity.translate(tx, ty).scale(scale);
    (animate ? svg.transition().duration(480) : svg).call(_zoom.transform, transform);
}

function zoomIn()  { if (_zoom && svg) svg.transition().duration(260).call(_zoom.scaleBy, 1.4); }
function zoomOut() { if (_zoom && svg) svg.transition().duration(260).call(_zoom.scaleBy, 1 / 1.4); }
function zoomFit() { fitToView(true); }

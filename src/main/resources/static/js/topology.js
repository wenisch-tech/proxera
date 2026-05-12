/**
 * topology.js — D3.js force-directed card-based graph for Proxera topology.
 * Nodes render as labelled cards with custom SVG icons and meta information.
 */

// Card dimensions (centered on node position)
const CARD_W = 130;
const CARD_H = 70;
const HALF_W = CARD_W / 2;
const HALF_H = CARD_H / 2;
const ICON_CY = -18;  // icon group Y offset from card center
const NAME_Y  =  4;   // name text baseline Y from card center
const META_Y  = 18;   // meta text baseline Y from card center

let simulation, svg, linkGroup, nodeGroup;

// ── Color helpers ─────────────────────────────────────────────────────────────
function nodeAccent(d) {
    if (d.type === 'server') return '#0d6efd';
    if (d.type === 'client') return d.connected ? '#198754' : '#6c757d';
    return d.enabled ? '#f59e0b' : '#6c757d';
}

function metaColor(d) {
    if (d.type === 'server') return 'rgba(255,255,255,0.45)';
    if (d.type === 'client') return d.connected ? '#6be39c' : 'rgba(255,255,255,0.35)';
    return d.enabled ? '#fbbf24' : 'rgba(255,255,255,0.35)';
}

function metaText(d) {
    if (d.type === 'server') {
        const id = String(d.id);
        return id.length > 18 ? id.slice(0, 16) + '\u2026' : id;
    }
    if (d.type === 'client') return d.connected ? '\u25cf Connected' : '\u25cb Offline';
    // route — show target host:port
    if (d.target) {
        const t = String(d.target);
        return '\u2192 ' + (t.length > 17 ? t.slice(0, 15) + '\u2026' : t);
    }
    return d.enabled ? '\u25cf Enabled' : '\u25cb Disabled';
}

// ── Custom SVG icons (all drawn centered at 0,0) ──────────────────────────────
function drawIcon(g, d) {
    const c = nodeAccent(d);

    if (d.type === 'server') {
        // Server rack — two shelf slots with LEDs and activity bars
        g.append('rect')
            .attr('x', -9).attr('y', -9).attr('width', 18).attr('height', 6)
            .attr('rx', 1.5).attr('fill', 'none').attr('stroke', c).attr('stroke-width', 1.5);
        g.append('rect')
            .attr('x', -9).attr('y', -1).attr('width', 18).attr('height', 6)
            .attr('rx', 1.5).attr('fill', 'none').attr('stroke', c).attr('stroke-width', 1.5);
        // LEDs
        g.append('circle').attr('cx', -6).attr('cy', -6).attr('r', 1.5).attr('fill', c);
        g.append('circle').attr('cx', -6).attr('cy',  2).attr('r', 1.5).attr('fill', c);
        // Activity bars
        g.append('rect').attr('x', -2).attr('y', -8).attr('width', 7).attr('height', 2)
            .attr('rx', 0.5).attr('fill', c).attr('opacity', 0.5);
        g.append('rect').attr('x', -2).attr('y',  0).attr('width', 7).attr('height', 2)
            .attr('rx', 0.5).attr('fill', c).attr('opacity', 0.5);

    } else if (d.type === 'client') {
        // Monitor + stand
        g.append('rect')
            .attr('x', -9).attr('y', -9).attr('width', 18).attr('height', 13)
            .attr('rx', 1.5).attr('fill', 'none').attr('stroke', c).attr('stroke-width', 1.5);
        // Inner screen fill
        g.append('rect')
            .attr('x', -7).attr('y', -7).attr('width', 14).attr('height', 9)
            .attr('rx', 1).attr('fill', c).attr('opacity', 0.1);
        // Base line
        g.append('line')
            .attr('x1', -9).attr('y1', 4).attr('x2', 9).attr('y2', 4)
            .attr('stroke', c).attr('stroke-width', 1.5);
        // Stand stem
        g.append('line')
            .attr('x1', 0).attr('y1', 4).attr('x2', 0).attr('y2', 8)
            .attr('stroke', c).attr('stroke-width', 1.5);
        // Stand foot
        g.append('line')
            .attr('x1', -4).attr('y1', 8).attr('x2', 4).attr('y2', 8)
            .attr('stroke', c).attr('stroke-width', 1.5);

    } else {
        // Route — vertical pole + two directional signpost arrows
        g.append('line')
            .attr('x1', 0).attr('y1', -9).attr('x2', 0).attr('y2', 9)
            .attr('stroke', c).attr('stroke-width', 1.5);
        // Upper right-pointing sign
        g.append('path')
            .attr('d', 'M0,-8 L7,-8 L9,-5 L7,-2 L0,-2 Z')
            .attr('fill', c).attr('opacity', 0.35)
            .attr('stroke', c).attr('stroke-width', 1).attr('stroke-linejoin', 'round');
        // Lower left-pointing sign
        g.append('path')
            .attr('d', 'M0,2 L-7,2 L-9,5 L-7,8 L0,8 Z')
            .attr('fill', c).attr('opacity', 0.35)
            .attr('stroke', c).attr('stroke-width', 1).attr('stroke-linejoin', 'round');
    }
}

// ── Graph init ────────────────────────────────────────────────────────────────
function initGraph() {
    const canvas = document.getElementById('topology-canvas');
    const WIDTH  = (canvas.clientWidth  > 0 ? canvas.clientWidth  : canvas.getBoundingClientRect().width  || 900);
    const HEIGHT = (canvas.clientHeight > 0 ? canvas.clientHeight : canvas.getBoundingClientRect().height || 420);

    svg = d3.select('#topology-canvas')
        .append('svg')
        .attr('width',  '100%')
        .attr('height', HEIGHT);

    const defs = svg.append('defs');

    // Drop-shadow filter for cards
    const shadow = defs.append('filter')
        .attr('id', 'card-shadow')
        .attr('x', '-30%').attr('y', '-30%')
        .attr('width', '160%').attr('height', '160%');
    shadow.append('feDropShadow')
        .attr('dx', 0).attr('dy', 6).attr('stdDeviation', 10)
        .attr('flood-color', '#000').attr('flood-opacity', 0.5);

    linkGroup = svg.append('g').attr('class', 'links');
    nodeGroup = svg.append('g').attr('class', 'nodes');

    simulation = d3.forceSimulation()
        .force('link',      d3.forceLink().id(d => d.id).distance(220))
        .force('charge',    d3.forceManyBody().strength(-700))
        .force('center',    d3.forceCenter(WIDTH / 2, HEIGHT / 2))
        .force('collision', d3.forceCollide(90));
}

// ── Graph render ──────────────────────────────────────────────────────────────
function renderGraph(data) {
    const nodes = data.nodes;
    const links = data.links;

    // Links — styled by type (tunnel = solid cyan, route = dashed amber)
    const link = linkGroup.selectAll('line')
        .data(links, d => `${d.source.id || d.source}-${d.target.id || d.target}`)
        .join('line')
        .attr('class', 'topo-link')
        .attr('stroke', d => d.type === 'tunnel'
            ? 'rgba(13,202,240,0.40)'
            : 'rgba(245,158,11,0.35)')
        .attr('stroke-width',     d => d.type === 'tunnel' ? 2 : 1.5)
        .attr('stroke-dasharray', d => d.type === 'route'  ? '5,4' : null);

    // Nodes
    const node = nodeGroup.selectAll('g.node')
        .data(nodes, d => d.id)
        .join(
            enter => {
                const g = enter.append('g')
                    .attr('class', 'node')
                    .style('cursor', 'grab')
                    .call(d3.drag()
                        .on('start', dragStart)
                        .on('drag',  dragged)
                        .on('end',   dragEnd));

                // Card background
                g.append('rect')
                    .attr('width',  CARD_W).attr('height', CARD_H)
                    .attr('x', -HALF_W).attr('y', -HALF_H)
                    .attr('rx', 10)
                    .attr('fill',   d => nodeAccent(d) + '1a')
                    .attr('stroke', d => nodeAccent(d))
                    .attr('stroke-width', 1.5)
                    .attr('stroke-opacity', 0.65)
                    .attr('filter', 'url(#card-shadow)');

                // Top accent bar — rounded top corners
                g.append('rect')
                    .attr('width', CARD_W).attr('height', 5)
                    .attr('x', -HALF_W).attr('y', -HALF_H)
                    .attr('rx', 10)
                    .attr('fill', d => nodeAccent(d))
                    .attr('opacity', 0.85);
                // Square off bottom corners of accent bar
                g.append('rect')
                    .attr('width', CARD_W).attr('height', 3)
                    .attr('x', -HALF_W).attr('y', -HALF_H + 2)
                    .attr('fill', d => nodeAccent(d))
                    .attr('opacity', 0.85);

                // Icon group centered at ICON_CY
                const iconG = g.append('g')
                    .attr('transform', `translate(0, ${ICON_CY})`);
                iconG.each(function(d) { drawIcon(d3.select(this), d); });

                // Node name
                g.append('text')
                    .attr('y', NAME_Y)
                    .attr('text-anchor', 'middle')
                    .attr('font-size',   '11.5px')
                    .attr('font-weight', '600')
                    .attr('font-family', 'Inter, system-ui, sans-serif')
                    .attr('fill', '#e2e8f0')
                    .text(d => d.name.length > 16 ? d.name.slice(0, 14) + '\u2026' : d.name);

                // Meta text (status / target)
                g.append('text')
                    .attr('y', META_Y)
                    .attr('text-anchor', 'middle')
                    .attr('font-size',   '9.5px')
                    .attr('font-family', 'Inter, system-ui, sans-serif')
                    .attr('fill', d => metaColor(d))
                    .text(d => metaText(d));

                return g;
            }
        );

    simulation.nodes(nodes).on('tick', () => {
        link
            .attr('x1', d => d.source.x)
            .attr('y1', d => d.source.y)
            .attr('x2', d => d.target.x)
            .attr('y2', d => d.target.y);
        node.attr('transform', d => `translate(${d.x},${d.y})`);
    });

    simulation.force('link').links(links);
    simulation.alpha(0.5).restart();
}

// ── Public API ────────────────────────────────────────────────────────────────
function refreshTopology() {
    fetch('/admin/topology/data')
        .then(r => r.json())
        .then(data => {
            if (!svg) initGraph();
            renderGraph(data);
        });
}

function pulseLink(event) {
    linkGroup.selectAll('line')
        .filter(d => {
            const tid = d.target.id || d.target;
            const sid = d.source.id || d.source;
            return tid === event.clientId || sid === event.clientId;
        })
        .classed('link-pulsing', true);
}

function clearPulse(event) {
    linkGroup.selectAll('line')
        .filter(d => {
            const tid = d.target.id || d.target;
            const sid = d.source.id || d.source;
            return tid === event.clientId || sid === event.clientId;
        })
        .classed('link-pulsing', false);
}

// ── Drag handlers ─────────────────────────────────────────────────────────────
function dragStart(event, d) {
    if (!event.active) simulation.alphaTarget(0.3).restart();
    d.fx = d.x;
    d.fy = d.y;
}

function dragged(event, d) {
    d.fx = event.x;
    d.fy = event.y;
}

function dragEnd(event, d) {
    if (!event.active) simulation.alphaTarget(0);
    d.fx = null;
    d.fy = null;
}


/**
 * topology.js — D3.js force-directed graph for the Proxera topology page.
 * Loaded on /admin/topology. Data fetched from /admin/topology/data (JSON).
 */

const WIDTH = document.getElementById('topology-canvas').clientWidth || 900;
const HEIGHT = 600;

let simulation, svg, linkGroup, nodeGroup;

function initGraph() {
    svg = d3.select('#topology-canvas')
        .append('svg')
        .attr('width', '100%')
        .attr('height', HEIGHT);

    svg.append('defs').append('marker')
        .attr('id', 'arrow')
        .attr('viewBox', '0 -5 10 10')
        .attr('refX', 24)
        .attr('refY', 0)
        .attr('markerWidth', 6)
        .attr('markerHeight', 6)
        .attr('orient', 'auto')
        .append('path')
        .attr('d', 'M0,-5L10,0L0,5')
        .attr('fill', '#6c757d');

    linkGroup = svg.append('g').attr('class', 'links');
    nodeGroup = svg.append('g').attr('class', 'nodes');

    simulation = d3.forceSimulation()
        .force('link', d3.forceLink().id(d => d.id).distance(120))
        .force('charge', d3.forceManyBody().strength(-400))
        .force('center', d3.forceCenter(WIDTH / 2, HEIGHT / 2))
        .force('collision', d3.forceCollide(40));
}

function nodeColor(d) {
    if (d.type === 'server') return '#0d6efd';
    if (d.type === 'client') return d.connected ? '#198754' : '#6c757d';
    return '#ffc107';
}

function nodeShape(d) {
    if (d.type === 'route') return 'diamond';
    return 'circle';
}

function renderGraph(data) {
    const nodes = data.nodes;
    const links = data.links;

    // Links
    const link = linkGroup.selectAll('line')
        .data(links, d => d.source + '-' + d.target)
        .join('line')
        .attr('class', 'topo-link')
        .attr('stroke', '#444c56')
        .attr('stroke-width', 1.5)
        .attr('marker-end', 'url(#arrow)');

    // Nodes
    const node = nodeGroup.selectAll('g.node')
        .data(nodes, d => d.id)
        .join(
            enter => {
                const g = enter.append('g').attr('class', 'node').call(
                    d3.drag()
                        .on('start', dragStart)
                        .on('drag', dragged)
                        .on('end', dragEnd)
                );
                g.append('circle')
                    .attr('r', d => d.type === 'server' ? 22 : (d.type === 'client' ? 18 : 14))
                    .attr('fill', nodeColor)
                    .attr('stroke', '#0d1117')
                    .attr('stroke-width', 2);
                g.append('text')
                    .attr('class', 'topo-label')
                    .attr('dy', d => d.type === 'client' ? 32 : 30)
                    .attr('text-anchor', 'middle')
                    .text(d => d.name);
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
        .filter(d => d.target.id === event.clientId || d.source.id === event.clientId)
        .classed('link-pulsing', true);
}

function clearPulse(event) {
    linkGroup.selectAll('line')
        .filter(d => d.target.id === event.clientId || d.source.id === event.clientId)
        .classed('link-pulsing', false);
}

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

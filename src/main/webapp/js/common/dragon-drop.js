const dragState = {
    x: 0,
    y: 0,
}

export function dragged(e) {
    dragState.x = 0;
    dragState.y = 0;
    e.target.style.transform = 'translate(0px, 0px)';

    // reset the position attributes for draggables (on failed drag, it will resume from the last position)
    e.target.removeAttribute("data-x")
    e.target.removeAttribute("data-y")
}

export function dragMoveListener (event) {
    dragState.x += event.dx;
    dragState.y += event.dy;

    event.target.style.transform = 'translate(' + dragState.x + 'px, ' + dragState.y + 'px)';

    const target = event.target
    // keep the dragged position in the data-x/data-y attributes
    const x = (parseFloat(target.getAttribute('data-x')) || 0) + event.dx
    const y = (parseFloat(target.getAttribute('data-y')) || 0) + event.dy

    // translate the element
    target.style.transform = 'translate(' + x + 'px, ' + y + 'px)'

    // update the position attributes
    target.setAttribute('data-x', x)
    target.setAttribute('data-y', y)
}

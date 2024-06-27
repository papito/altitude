function highlightNav(elClass) {
    const fullClassName = `nav .menu.${elClass}`
    document.querySelector(fullClassName).classList.add("active");
}

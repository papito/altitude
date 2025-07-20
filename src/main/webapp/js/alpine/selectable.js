export function initSelectable() {
    const ASSET_ID_SET_NAME = "selectedAssets"
    Alpine.store(ASSET_ID_SET_NAME, {
        ids: new Set(),
        get isEmpty() {
            return this.ids.size === 0;
        },
    })

    Alpine.data('selectable', () => ({
        selected: false,
        select(){
            const assetId = this.$el.parentElement.getAttribute('alt-asset-id')

            if (this.selected) {
                Alpine.store(ASSET_ID_SET_NAME).ids.delete(assetId)
            } else {
                Alpine.store(ASSET_ID_SET_NAME).ids.add(assetId)
            }

            this.selected = !this.selected
        }
    }))
}

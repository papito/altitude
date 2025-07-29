import { Folder } from "../models/folder.js"
import { showErrorSnackBar, showSuccessSnackBar } from "../common/snackbar.js"

class AssetService {
    moveAssets({ folderId, assetIds }) {
        const newParentFolder = new Folder(folderId)

        const payload = {
            assetIds: assetIds,
            folderId: folderId
        }

        fetch(`/api/asset/r/${window.ctx.getRepoId()}/move`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        })
            .then(response => {
                if (!response.ok) {
                    showErrorSnackBar(`Error  + ${response.statusText}`)
                }
                const successMessage = `${assetIds.size > 0 ? 'Assets' : 'Asset'} moved to folder "${newParentFolder.name()}"`
                showSuccessSnackBar(successMessage)
                // removeAssetFromResultSetUtil(event, response, successMessage)
            })
            .catch((response) => {
                showErrorSnackBar(`Error moving  asset: ${response.status}, ${response.statusText}`)
            })
    }

    recycleAssets({ assetIds }) {
        const payload = {
            assetIds: assetIds,
        }

        fetch(`/api/asset/r/${window.ctx.getRepoId()}/move`, {
            method: 'DELETE',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        })
            .then(response => {
                if (!response.ok) {
                    showErrorSnackBar(`Error  + ${response.statusText}`)
                }
                const successMessage = `${assetIds.size > 0 ? 'Assets' : 'Asset'} moved to the trash bin"`
                showSuccessSnackBar(successMessage)
                // removeAssetFromResultSetUtil(event, response, successMessage)
            })
            .catch((response) => {
                showErrorSnackBar(`Error moving  asset: ${response.status}, ${response.statusText}`)
            })
    }
}

const assetService = new AssetService();
export default assetService;

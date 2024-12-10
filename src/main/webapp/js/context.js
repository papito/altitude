import { Const } from "./constants.js"
import { Alpine } from "./lib/alpine.esm.min.js"

/**
 * This module is used to store the context of the current user/request.
 *
 * If a module requires the context (for URL generation, etc.), the parent SPP template can set the context
 * as shown below:
 *
 * <script type="module">
 *     import {context} from "../context.js"
 *
 *      context.setRepoId("<%= RequestContext.getRepository.persistedId %>")
 * </script>
 */
export const context = {
    setRepoId: function (repoId) {
        Alpine.store(Const.context.repoId, repoId)
    },
    getRepoId: function () {
        return Alpine.store(Const.context.repoId)
    },
}

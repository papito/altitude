/**
 * This module is used to store the context of the current user/request.
 *
 * If a module requires the context (for URL generation, etc.), the parent SPP template can set the context
 * as shown below:
 *
 * <script type="module">
 *     import {context} from "/js/context.js"
 *
 *      context.repoId = "${ RequestContext.getRepository.persistedId }"
 * </script>
 */
export const context = {
    repoId: null,
}

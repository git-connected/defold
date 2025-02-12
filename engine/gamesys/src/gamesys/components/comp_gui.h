// Copyright 2020 The Defold Foundation
// Licensed under the Defold License version 1.0 (the "License"); you may not use
// this file except in compliance with the License.
//
// You may obtain a copy of the License, together with FAQs at
// https://www.defold.com/license
//
// Unless required by applicable law or agreed to in writing, software distributed
// under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
// CONDITIONS OF ANY KIND, either express or implied. See the License for the
// specific language governing permissions and limitations under the License.

#ifndef DM_GAMESYS_COMP_GUI_H
#define DM_GAMESYS_COMP_GUI_H

#include <gameobject/component.h>

namespace dmGameSystem
{
    dmGameObject::CreateResult CompGuiNewWorld(const dmGameObject::ComponentNewWorldParams& params);

    dmGameObject::CreateResult CompGuiDeleteWorld(const dmGameObject::ComponentDeleteWorldParams& params);

    dmGameObject::CreateResult CompGuiCreate(const dmGameObject::ComponentCreateParams& params);

    dmGameObject::CreateResult CompGuiDestroy(const dmGameObject::ComponentDestroyParams& params);

    dmGameObject::CreateResult CompGuiInit(const dmGameObject::ComponentInitParams& params);

    dmGameObject::CreateResult CompGuiFinal(const dmGameObject::ComponentFinalParams& params);

    dmGameObject::CreateResult CompGuiAddToUpdate(const dmGameObject::ComponentAddToUpdateParams& params);

    dmGameObject::UpdateResult CompGuiUpdate(const dmGameObject::ComponentsUpdateParams& params, dmGameObject::ComponentsUpdateResult& update_result);

    dmGameObject::UpdateResult CompGuiRender(const dmGameObject::ComponentsRenderParams& params);

    dmGameObject::UpdateResult CompGuiOnMessage(const dmGameObject::ComponentOnMessageParams& params);

    dmGameObject::InputResult CompGuiOnInput(const dmGameObject::ComponentOnInputParams& params);

    void CompGuiOnReload(const dmGameObject::ComponentOnReloadParams& params);

    dmGameObject::PropertyResult CompGuiGetProperty(const dmGameObject::ComponentGetPropertyParams& params, dmGameObject::PropertyDesc& out_value);

    dmGameObject::PropertyResult CompGuiSetProperty(const dmGameObject::ComponentSetPropertyParams& params);

    void CompGuiIterChildren(dmGameObject::SceneNodeIterator* it, dmGameObject::SceneNode* node);
    void CompGuiIterProperties(dmGameObject::SceneNodePropertyIterator* pit, dmGameObject::SceneNode* node);
}

#endif // DM_GAMESYS_COMP_GUI_H

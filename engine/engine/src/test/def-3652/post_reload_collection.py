# Copyright 2020 The Defold Foundation
# Licensed under the Defold License version 1.0 (the "License"); you may not use
# this file except in compliance with the License.
#
# You may obtain a copy of the License, together with FAQs at
# https://www.defold.com/license
#
# Unless required by applicable law or agreed to in writing, software distributed
# under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
# CONDITIONS OF ANY KIND, either express or implied. See the License for the
# specific language governing permissions and limitations under the License.

import sys, os
sys.path = ['build/default/proto/engine'] + sys.path
sys.path = [os.path.join(os.environ['DYNAMO_HOME'], 'lib/python/resource')] + sys.path
import resource_ddf_pb2, httplib

m = resource_ddf_pb2.Reload()
m.resources.append("/def-3652/def-3652.collectionc")

conn = httplib.HTTPConnection("localhost", int(sys.argv[1]))
conn.request("POST", "/post/@resource/reload", m.SerializeToString())
response = conn.getresponse()
data = response.read()
conn.close()
assert response.status == 200

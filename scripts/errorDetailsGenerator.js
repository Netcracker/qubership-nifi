/**
 * Copyright 2020-2025 NetCracker Technology Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

var flowFile = session.get();
if (flowFile !== null) {
  var fullUrl = flowFile.getAttribute("invokehttp.request.url");
  var httpCode = flowFile.getAttribute("invokehttp.status.code");
  var responceBody = flowFile.getAttribute("invokehttp.response.body");
  var detailMessage =
    "Error " + httpCode + ' during invoke "' + fullUrl + '". ';

  if (responceBody != null && responceBody != "") {
    detailMessage = detailMessage + "Request return: " + responceBody;
  }

  flowFile = session.putAttribute(flowFile, "title", getMessage(httpCode));
  flowFile = session.putAttribute(flowFile, "error.details", detailMessage);

  session.transfer(flowFile, REL_SUCCESS);
}

function getMessage(code) {
  switch (code) {
    case "400":
      return "HTTP status code 400: Bad Request";
    case "401":
      return "HTTP status code 401: Unauthorized";
    case "404":
      return "HTTP status code 404: Not Found";
    case "408":
      return "HTTP status code 408: Request Timeout";
    default:
      return "HTTP status code " + code;
  }
}

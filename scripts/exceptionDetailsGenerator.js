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
  var key = flowFile
    .getAttribute("invokehttp.java.exception.class")
    .replace(/.*\./g, "");
  var errorPrefix = key + ' during invoke "' + fullUrl + '". ';
  var exceptionMessage = flowFile.getAttribute(
    "invokehttp.java.exception.message",
  );
  session.putAttribute(flowFile, "error.code", "CIM-IE-0000");

  if (exceptionMessage != null && exceptionMessage != "") {
    exceptionMessage = "Exception message: " + exceptionMessage;
  }

  putExceptionAttributes(key);

  session.transfer(flowFile, REL_SUCCESS);
}

function putExceptionAttributes(key) {
  var title = "title";
  var details = "error.details";
  switch (key) {
    case "SocketTimeoutException":
      session.putAttribute(
        flowFile,
        title,
        "Socket timeout during HTTP invoke.",
      );
      session.putAttribute(
        flowFile,
        details,
        errorPrefix +
          "Timeout has occurred on a socket read or accept. " +
          exceptionMessage,
      );
      break;
    case "UnknownHostException":
      session.putAttribute(
        flowFile,
        title,
        "Unknown host in HTTP invoke process.",
      );
      session.putAttribute(
        flowFile,
        details,
        errorPrefix +
          "IP address of a host could not be determined. " +
          exceptionMessage,
      );
      break;
    case "ConnectException":
      session.putAttribute(
        flowFile,
        title,
        "Connection error during HTTP invoke.",
      );
      session.putAttribute(
        flowFile,
        details,
        errorPrefix +
          "Error occurred while attempting to connect a socket to a remote address and port. " +
          exceptionMessage,
      );
      break;
    case "SocketException":
      session.putAttribute(
        flowFile,
        title,
        "Socket error in HTTP invoke process.",
      );
      session.putAttribute(
        flowFile,
        details,
        errorPrefix +
          "Error creating or accessing a Socket. " +
          exceptionMessage,
      );
      break;
    case "NoRouteToHostException":
      session.putAttribute(flowFile, title, "Remote host cannot be reached.");
      session.putAttribute(
        flowFile,
        details,
        errorPrefix +
          "Error occurred while attempting to connect a socket to a remote address and port. " +
          exceptionMessage,
      );
      break;

    default:
      session.putAttribute(
        flowFile,
        "title",
        key + " in HTTP  invoke process.",
      );
      session.putAttribute(
        flowFile,
        "error.details",
        errorPrefix + exceptionMessage,
      );
  }
}

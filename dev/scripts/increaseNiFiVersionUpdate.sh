#!/bin/bash

echo "Start update flow to NiFi 2.x.x version"
configFile=$(cat "$pathToUpdateNiFiConfig")
for expflow in "${listForUpdate[@]}"
do
	echo "Replacing artifact and type based on mapping file for flow - $expflow"
	tmp=$(mktemp)
	jq --argjson file "$configFile" 'walk(if type == "object" and .type != null and $file[.type] != null then if $file[.type].newArtifact != null then .bundle.artifact = $file[.type].newArtifact | .type = $file[.type].newType else .type = $file[.type].newType end else . end )' "$expflow" > "$tmp" || handle_error "Error while replacing artifact and type in flow - $expflow"
	if [ "$DEBUG_MODE" = "true" ]; then
		echo "DEBUG: diff between $expflow и $tmp"
		diff "$expflow" "$tmp"
	fi
	mv "$tmp" "$expflow"
	
	echo "Replacing properties names for JoltTransformJSON processor for flow - $expflow"
	tmp2=$(mktemp)
	jq 'walk(if type == "object" and .type != null and .type == "org.apache.nifi.processors.jolt.JoltTransformJSON" then .properties |= with_entries(if .key == "jolt-spec" then .key = "Jolt Specification" elif .key == "jolt-transform" then .key = "Jolt Transform" elif .key == "pretty_print" then .key = "Pretty Print" else .key |= . end ) else . end)' "$expflow" > "$tmp2" || handle_error "Error while executing replacement properties for JoltTransformJSON processor in flow - $expflow" #add message
	if [ "$DEBUG_MODE" = "true" ]; then
		echo "DEBUG: diff between $expflow и $tmp2"
		diff "$expflow" "$tmp2"
	fi
	mv "$tmp2" "$expflow"
done

echo "Finish update flow to NiFi 2.x.x version"
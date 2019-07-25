#!/bin/bash

function chars() {
    s="$1"
    while [ -n "$s" ]; do
        echo -n "${s:0:1}"
        s="${s:1}"
        sleep 0.06
    done
    echo
}

skip=0
while IFS= read line; do
    if [ $skip -gt 0 ]; then
        skip=$(expr $skip - 1)
    else
        case "$line" in
            ('# sleep'*) sleep $(echo $line | cut -d' ' -f3);;
            ('# skip'*) skip=$(echo $line | cut -d' ' -f3);;
            (*) sleep 0.7; chars "$line";;
        esac
    fi
done
echo

# End play.sh

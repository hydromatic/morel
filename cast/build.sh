#!/bin/bash
#
# Generates an asciinema screencast from a script.
# You must install asciinema first; try 'brew install asciinema'.
# Note that this script destroys ~/smlj.
# Use terminal of height 16, width 72.
# Usage:
#   build.sh ~/dev/smlj/cast/smlj-0.1.0.txt
DIR=$(cd $(dirname $0); pwd)
INFILE="$1"
OUTFILE=$(dirname "${INFILE}")/$(basename "${INFILE}" .txt).cast
if [ ! -f "${INFILE}" ]; then
    echo "Not found: ${INFILE}"
    exit 1
fi
cat ${INFILE} |
    ${DIR}/play.sh |
    (
        cd ~
        rm -rf ~/smlj
        asciinema rec -t"smlj 0.1.0" --stdin --overwrite "${OUTFILE}"
    )
# End build.sh

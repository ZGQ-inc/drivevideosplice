#!/bin/bash

while read -r filepath; do
    filename=$(basename "$filepath")
    
    output="${filename}.MP4"
    
    ffmpeg -f lavfi -i color=c=black:s=240x240:d=5 \
           -vf "drawtext=fontfile=/path/to/font.ttf:text='${filename}':fontcolor=white:fontsize=24:x=(w-text_w)/2:y=(h-text_h)/2" \
           -c:v libx264 -pix_fmt yuv420p -y "$output"
done < filelist.txt
 < filelist.txt

(*
 * Licensed to Julian Hyde under one or more contributor license
 * agreements.  See the NOTICE file distributed with this work
 * for additional information regarding copyright ownership.
 * Julian Hyde licenses this file to you under the Apache
 * License, Version 2.0 (the "License"); you may not use this
 * file except in compliance with the License.  You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the specific
 * language governing permissions and limitations under the
 * License.
 *
 * The DATE signature, per the Standard ML Basis Library.
 *)
(**
 * The `Date` structure provides an abstract type for calendar dates and
 * times, with fields for year, month, day, hour, minute, and second. Dates
 * can be constructed from `Time.time` values, decomposed into fields,
 * formatted as strings, and compared.
 *)
signature DATE =
sig

  (**
   * is an equality type representing a calendar date and time of day,
   * with an associated timezone offset.
   *)
  eqtype date

  (** is the type of month values. *)
  datatype month = Jan | Feb | Mar | Apr | May | Jun | Jul | Aug | Sep | Oct | Nov | Dec

  (** is the type of weekday values. *)
  datatype weekday = Mon | Tue | Wed | Thu | Fri | Sat | Sun

  (**
   * is raised when a date cannot be constructed from the given fields
   * (for example, if the day or month is out of range).
   *)
  exception Date

  (**
   * returns `LESS`, `EQUAL`, or `GREATER` depending on whether `d1` is less
   * than, equal to, or greater than `d2` (comparing instants in time).
   *)
  val compare : date * date -> `order`
      [@@method] [@@prototype "compare (d1, d2)"]

  (**
   * constructs a date from the given fields. If `offset` is `NONE`, the date
   * is in local time; if `SOME t`, the date is in the timezone with offset `t`
   * from UTC.
   *)
  val date : {day:int, hour:int, minute:int, month:month,
              offset:time option, second:int, year:int}
             -> date
      [@@prototype "date {year, month, day, hour, minute, second, offset}"]

  (** returns the day of the month of `d`, in the range `[1, 31]`. *)
  val day : date -> int [@@method] [@@prototype "day d"]

  (**
   * formats `d` using the strftime-style format string `s`. Recognized
   * format codes include `%Y` (4-digit year), `%m` (2-digit month),
   * `%d` (2-digit day), `%H` (hour), `%M` (minute), `%S` (second),
   * `%a` (abbreviated weekday), `%b` (abbreviated month), and `%%` (literal `%`).
   *)
  val fmt : string -> date -> string [@@prototype "fmt s d"]

  (**
   * parses a date from the string `s`, which should be in the format
   * produced by `toString` (e.g., `"Thu Jan  1 00:00:00 1970"`).
   * Returns `SOME d` if successful, `NONE` otherwise.
   *)
  val fromString : string -> date option [@@prototype "fromString s"]

  (** converts the time value `t` to a date in the local timezone. *)
  val fromTimeLocal : time -> date [@@prototype "fromTimeLocal t"]

  (** converts the time value `t` to a date in UTC. *)
  val fromTimeUniv : time -> date [@@prototype "fromTimeUniv t"]

  (** returns the hour of `d`, in the range `[0, 23]`. *)
  val hour : date -> int [@@method] [@@prototype "hour d"]

  (**
   * returns `SOME true` if `d` is in daylight saving time, `SOME false` if
   * not, or `NONE` if the information is not available.
   *)
  val isDst : date -> bool option [@@method] [@@prototype "isDst d"]

  (**
   * returns the offset of the local timezone from UTC as a `time` value
   * (nanoseconds).
   *)
  val localOffset : unit -> time [@@prototype "localOffset ()"]

  (** returns the minute of `d`, in the range `[0, 59]`. *)
  val minute : date -> int [@@method] [@@prototype "minute d"]

  (** returns the month of `d`. *)
  val month : date -> month [@@method] [@@prototype "month d"]

  (** returns the second of `d`, in the range `[0, 59]`. *)
  val second : date -> int [@@method] [@@prototype "second d"]

  (**
   * formats `d` as a string in the format `"Www Mmm DD HH:MM:SS YYYY"`,
   * for example `"Thu Jan  1 00:00:00 1970"`.
   *)
  val toString : date -> string [@@method] [@@prototype "toString d"]

  (**
   * converts `d` to a `time` value (nanoseconds since the Unix epoch).
   *)
  val toTime : date -> time [@@method] [@@prototype "toTime d"]

  (** returns the day of the week of `d`. *)
  val weekDay : date -> weekday [@@method] [@@prototype "weekDay d"]

  (** returns the year of `d`. *)
  val year : date -> int [@@method] [@@prototype "year d"]

  (** returns the day of the year of `d`, in the range `[0, 365]`. *)
  val yearDay : date -> int [@@method] [@@prototype "yearDay d"]
end
[@@description "Calendar date and time values."]

(*) End date.sig
